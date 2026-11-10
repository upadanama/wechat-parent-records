package com.jz.chatshot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import java.time.LocalDate
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

    suspend fun findContacts(svc: AccessibilityService, term: String): List<String> {
        if (term.isEmpty()) return emptyList()
        if (!ensureWeChat(svc)) return emptyList()

        var searchEntry: AccessibilityNodeInfo? = null
        for (attempt in 0..4) {
            searchEntry = findNode(svc.rootInActiveWindow) { node ->
                val text = node.text?.toString()?.trim()
                val desc = node.contentDescription?.toString()?.trim()
                (text == "搜索" || desc == "搜索") && node.isVisibleToUser && hasClickableAncestor(node)
            }
            if (searchEntry != null) break
            if (attempt < 4) {
                svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                delay(700)
            }
        }
        val entry = searchEntry ?: return emptyList()
        if (!clickNode(svc, entry)) return emptyList()
        delay(700)

        var editNode: AccessibilityNodeInfo? = null
        val hasEditor = waitFor(5000) {
            editNode = findNode(svc.rootInActiveWindow) { it.isEditable && it.isVisibleToUser }
            editNode != null
        }
        val editor = editNode
        if (!hasEditor || editor == null) return emptyList()

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, term)
        }
        var inputted = editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!inputted) {
            editor.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            inputted = editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }
        if (!inputted) return emptyList()

        delay(1500)
        val texts = ArrayList<String>()
        collectTexts(svc.rootInActiveWindow, texts)
        val start = texts.indexOfFirst { it == "联系人" }
        if (start < 0) return emptyList()

        val chars = term.toSet()
        val result = LinkedHashSet<String>()
        for (i in start + 1 until texts.size) {
            val text = texts[i].trim()
            if (text.isEmpty()) continue
            if (text in SECTION_TITLES) break
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

    private suspend fun ensureWeChat(svc: AccessibilityService): Boolean {
        if (svc.rootInActiveWindow?.packageName?.toString() == PKG) return true
        val intent = svc.packageManager.getLaunchIntentForPackage(PKG) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { svc.startActivity(intent) }
        return waitFor(5000) { svc.rootInActiveWindow?.packageName?.toString() == PKG }
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
