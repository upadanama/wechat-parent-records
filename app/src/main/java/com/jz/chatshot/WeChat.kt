package com.jz.chatshot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine

/** 只基于可见文字操作微信，不依赖任何 resource-id。 */
object WeChat {

    const val PKG = "com.tencent.mm"

    private val FULL_DATE = Regex("""(\d{4})年(\d{1,2})月(\d{1,2})日""")
    private val MONTH_DAY = Regex("""(\d{1,2})月(\d{1,2})日""")
    private val WEEKDAY = Regex("""(?:星期|周)([一二三四五六日天七])""")
    private val PURE_TIME = Regex("""^(?:上午|下午|中午|晚上|凌晨)?\s*\d{1,2}:\d{2}$""")
    private val SECTION_TITLES = setOf(
        "群聊", "聊天记录", "搜索网络结果", "搜一搜",
        "公众号", "小程序", "朋友圈", "视频号", "文章", "音乐", "表情"
    )
    private val START_TITLES = setOf("联系人", "最常使用")
    private val ALL_SECTION_TITLES = SECTION_TITLES + START_TITLES
    private val IGNORED_PREFIXES = listOf("更多", "微信号", "昵称", "来自", "包含")
    private val DUMP_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    suspend fun findContacts(svc: AccessibilityService, term: String): List<String> {
        if (term.isEmpty()) return emptyList()
        if (!ensureWeChat(svc)) return emptyList()

        var editor = findVisibleEditor(svc)
        if (editor != null && !isLikelySearchEditor(svc, editor)) {
            // 聊天页底部的输入框同样是 editable，不能误当成搜索框。
            editor = null
        }

        if (editor == null) {
            var searchEntry: AccessibilityNodeInfo? = findSearchEntry(svc.rootInActiveWindow)
            var backs = 0
            while (searchEntry == null && backs < 4) {
                if (!isWeChatForeground(svc)) {
                    if (!relaunchWeChat(svc)) return emptyList()
                    searchEntry = findSearchEntry(svc.rootInActiveWindow)
                    break
                }
                svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                backs++
                delay(700)
                if (!isWeChatForeground(svc)) {
                    if (!relaunchWeChat(svc)) return emptyList()
                    searchEntry = findSearchEntry(svc.rootInActiveWindow)
                    break
                }
                searchEntry = findSearchEntry(svc.rootInActiveWindow)
            }
            val entry = searchEntry ?: return emptyList()
            if (!clickNode(svc, entry)) return emptyList()
            delay(700)
            editor = waitForEditor(svc) ?: return emptyList()
        }

        val activeEditor = editor ?: return emptyList()
        if (!setSearchText(activeEditor, term)) return emptyList()

        // 先给微信一点时间替换上次结果，再最多轮询 4 秒等待任一分区标题。
        delay(700)
        val appeared = waitFor(4000) {
            findNode(svc.rootInActiveWindow) { node ->
                val text = nodeText(node)
                node.isVisibleToUser && text != null && text in ALL_SECTION_TITLES
            } != null
        }
        if (!appeared) return emptyList()

        val texts = ArrayList<String>()
        collectTexts(svc.rootInActiveWindow, texts)
        val start = texts.indexOfFirst { it == "联系人" || it == "最常使用" }
        if (start < 0) return emptyList()

        val chars = term.toSet()
        val result = LinkedHashSet<String>()
        for (i in start + 1 until texts.size) {
            val text = texts[i].trim()
            if (text.isEmpty()) continue
            if (text == "联系人" || text == "最常使用") continue
            if (text in SECTION_TITLES) break
            if (IGNORED_PREFIXES.any { text.startsWith(it) }) continue
            if (text.length <= 30 && text.any { it in chars }) result.add(text)
        }
        return result.toList()
    }

    suspend fun openContact(svc: AccessibilityService, contact: String): Boolean {
        val target = findNode(svc.rootInActiveWindow) { node ->
            node.isVisibleToUser && node.text?.toString()?.trim() == contact
        } ?: return false
        val clicked = clickNode(svc, target)
        if (clicked) delay(700)
        return clicked
    }

    /** 输出整棵无障碍节点树，用于适配不同微信版本。 */
    fun dump(root: AccessibilityNodeInfo?): String {
        val builder = StringBuilder()
        builder.append("窗口包名=")
            .append(root?.packageName?.toString() ?: "未知")
            .append(" 时间=")
            .append(LocalDateTime.now().format(DUMP_TIME_FORMATTER))
            .append('\n')
        if (root != null) appendNodeDump(builder, root, 0)
        return builder.toString()
    }

    private fun appendNodeDump(builder: StringBuilder, node: AccessibilityNodeInfo, depth: Int) {
        repeat(depth) { builder.append("  ") }
        val className = node.className?.toString()?.substringAfterLast('.')?.takeIf { it.isNotEmpty() } ?: "?"
        builder.append(className)
        val text = oneLine(node.text?.toString())
        if (text.isNotEmpty()) builder.append(" | text=").append(text.take(40))
        val desc = oneLine(node.contentDescription?.toString())
        if (desc.isNotEmpty()) builder.append(" | desc=").append(desc.take(40))
        val id = node.viewIdResourceName
        if (!id.isNullOrEmpty()) builder.append(" | id=").append(id)
        val flags = ArrayList<String>()
        if (node.isClickable) flags.add("clickable")
        if (node.isEditable) flags.add("editable")
        if (node.isScrollable) flags.add("scrollable")
        if (flags.isNotEmpty()) builder.append(" | ").append(flags.joinToString(","))
        val rect = Rect()
        node.getBoundsInScreen(rect)
        builder.append(" | bounds=").append(rect.toShortString())
        builder.append('\n')
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            appendNodeDump(builder, child, depth + 1)
        }
    }

    private fun oneLine(value: String?): String =
        value?.replace('\n', ' ')?.replace('\r', ' ')?.trim() ?: ""

    fun visibleDate(root: AccessibilityNodeInfo?): LocalDate? {
        if (root == null) return null
        val texts = ArrayList<PositionedText>()
        collectPositionedTexts(root, texts)
        texts.sortWith(compareBy<PositionedText> { it.top }.thenBy { it.left })
        for (item in texts) {
            parseVisibleDate(item.text)?.let { return it }
        }
        return null
    }

    private data class PositionedText(val top: Int, val left: Int, val text: String)

    private fun findVisibleEditor(svc: AccessibilityService): AccessibilityNodeInfo? =
        findNode(svc.rootInActiveWindow) { it.isEditable && it.isVisibleToUser }

    /** 只复用屏幕上半部分的编辑框，避免把聊天页底部的输入框当成搜索框。 */
    private fun isLikelySearchEditor(svc: AccessibilityService, editor: AccessibilityNodeInfo): Boolean {
        val rect = Rect()
        editor.getBoundsInScreen(rect)
        val height = svc.resources.displayMetrics.heightPixels
        return height > 0 && rect.centerY() < height / 2
    }

    private suspend fun waitForEditor(svc: AccessibilityService): AccessibilityNodeInfo? {
        var editor: AccessibilityNodeInfo? = null
        waitFor(5000) {
            editor = findVisibleEditor(svc)
            editor != null
        }
        return editor
    }

    private fun setSearchText(editor: AccessibilityNodeInfo, term: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, term)
        }
        if (editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return true
        editor.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        return editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findSearchEntry(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? =
        findNode(root) { node ->
            val text = node.text?.toString()?.trim()
            val desc = node.contentDescription?.toString()?.trim()
            (text == "搜索" || desc == "搜索") && node.isVisibleToUser && hasClickableAncestor(node)
        }

    private suspend fun relaunchWeChat(svc: AccessibilityService): Boolean {
        if (!ensureWeChat(svc)) return false
        delay(700)
        return true
    }

    private fun isWeChatForeground(svc: AccessibilityService): Boolean =
        svc.rootInActiveWindow?.packageName?.toString() == PKG

    private fun nodeText(node: AccessibilityNodeInfo): String? =
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }

    private suspend fun ensureWeChat(svc: AccessibilityService): Boolean {
        if (isWeChatForeground(svc)) return true
        val intent = svc.packageManager.getLaunchIntentForPackage(PKG) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { svc.startActivity(intent) }
        return waitFor(5000) { isWeChatForeground(svc) }
    }

    private suspend fun waitFor(timeoutMs: Long = 5000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            delay(200)
        }
        return condition()
    }

    private fun findNode(
        root: AccessibilityNodeInfo?,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (root == null) return null
        if (predicate(root)) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findNode(child, predicate)
            if (found != null) return found
        }
        return null
    }

    private fun collectTexts(node: AccessibilityNodeInfo?, out: MutableList<String>) {
        if (node == null) return
        if (node.isVisibleToUser) {
            val text = node.text?.toString()?.trim()
            if (!text.isNullOrEmpty()) out.add(text)
        }
        for (i in 0 until node.childCount) collectTexts(node.getChild(i), out)
    }

    private fun collectPositionedTexts(node: AccessibilityNodeInfo?, out: MutableList<PositionedText>) {
        if (node == null) return
        if (node.isVisibleToUser) {
            val text = node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                ?: node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            if (text != null) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                out.add(PositionedText(rect.top, rect.left, text))
            }
        }
        for (i in 0 until node.childCount) collectPositionedTexts(node.getChild(i), out)
    }

    private fun hasClickableAncestor(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 10) {
            if (current.isClickable) return true
            current = current.parent
            depth++
        }
        return false
    }

    private suspend fun clickNode(svc: AccessibilityService, node: AccessibilityNodeInfo?): Boolean {
        var current = node
        var depth = 0
        while (current != null && depth < 10) {
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            current = current.parent
            depth++
        }
        return tapCenter(svc, node)
    }

    private suspend fun tapCenter(svc: AccessibilityService, node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.width() <= 0 || rect.height() <= 0) return false
        val path = Path().apply {
            moveTo(rect.exactCenterX(), rect.exactCenterY())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 120))
            .build()
        return suspendCancellableCoroutine { continuation ->
            val callback = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(false)
                }
            }
            val dispatched = runCatching {
                svc.dispatchGesture(gesture, callback, null)
            }.getOrDefault(false)
            if (!dispatched && continuation.isActive) continuation.resume(false)
        }
    }

    private fun parseVisibleDate(text: String): LocalDate? {
        FULL_DATE.find(text)?.let { match ->
            val date = localDate(
                match.groupValues[1].toInt(),
                match.groupValues[2].toInt(),
                match.groupValues[3].toInt()
            )
            if (date != null) return date
        }
        MONTH_DAY.find(text)?.let { match ->
            val date = monthDay(match.groupValues[1].toInt(), match.groupValues[2].toInt())
            if (date != null) return date
        }
        if (text.contains("昨天")) return LocalDate.now().minusDays(1)
        WEEKDAY.find(text)?.let { match ->
            val target = weekdayValue(match.groupValues[1].firstOrNull() ?: return@let)
            if (target != null) {
                val today = LocalDate.now()
                var diff = today.dayOfWeek.value - target
                if (diff <= 0) diff += 7
                return today.minusDays(diff.toLong())
            }
        }
        if (PURE_TIME.matches(text)) return LocalDate.now()
        return null
    }

    private fun monthDay(month: Int, day: Int): LocalDate? {
        val today = LocalDate.now()
        val date = localDate(today.year, month, day) ?: return null
        return if (date.isAfter(today.plusDays(30))) date.minusYears(1) else date
    }

    private fun localDate(year: Int, month: Int, day: Int): LocalDate? =
        runCatching { LocalDate.of(year, month, day) }.getOrNull()

    private fun weekdayValue(char: Char): Int? = when (char) {
        '一' -> 1
        '二' -> 2
        '三' -> 3
        '四' -> 4
        '五' -> 5
        '六' -> 6
        '日', '天', '七' -> 7
        else -> null
    }
}
