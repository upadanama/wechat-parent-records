package com.jz.chatshot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.jz.chatshot.core.ChatTime
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine

enum class LocateResult { OK, NO_CHAT_THAT_DAY, CANNOT_READ_TIME, FAILED }

/** 只基于可见文字操作微信，不依赖任何 resource-id。 */
object WeChat {

    const val PKG = "com.tencent.mm"

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

        // 聊天页底部输入框也是 editable，必须同时看到搜索页特有的「取消」才复用。
        var editor = findSearchPageEditor(svc)
        var searchEntry = if (editor == null) findSearchEntry(svc.rootInActiveWindow) else null
        var backs = 0
        while (editor == null && searchEntry == null && backs < 4) {
            if (!isWeChatForeground(svc)) {
                if (!relaunchWeChat(svc)) return emptyList()
                editor = findSearchPageEditor(svc)
                searchEntry = if (editor == null) findSearchEntry(svc.rootInActiveWindow) else null
                break
            }
            svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            backs++
            delay(700)
            if (!isWeChatForeground(svc)) {
                if (!relaunchWeChat(svc)) return emptyList()
                editor = findSearchPageEditor(svc)
                searchEntry = if (editor == null) findSearchEntry(svc.rootInActiveWindow) else null
                break
            }
            editor = findSearchPageEditor(svc)
            if (editor == null) searchEntry = findSearchEntry(svc.rootInActiveWindow)
        }

        if (editor == null) {
            val entry = searchEntry ?: return emptyList()
            if (!clickNode(svc, entry)) return emptyList()
            delay(700)
            editor = waitForSearchPageEditor(svc) ?: return emptyList()
        }

        val activeEditor = editor ?: return emptyList()
        if (!setSearchText(activeEditor, term)) return emptyList()

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

    /** 当前屏幕里能完整解析的时间分隔，按 top 升序。 */
    fun visibleSeparators(root: AccessibilityNodeInfo?): List<Pair<LocalDateTime, Rect>> {
        val result = ArrayList<Pair<LocalDateTime, Rect>>()
        if (root != null) collectSeparators(root, result)
        return result.sortedWith(
            compareBy<Pair<LocalDateTime, Rect>> { it.second.top }.thenBy { it.second.left }
        )
    }

    fun visibleDate(root: AccessibilityNodeInfo?): LocalDate? =
        visibleSeparators(root).firstOrNull()?.first?.toLocalDate()

    suspend fun swipe(
        svc: AccessibilityService,
        older: Boolean,
        fraction: Float = 0.45f,
        avoid: Rect? = null
    ): Boolean {
        if (fraction <= 0f) return false
        val metrics = svc.resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        if (width <= 0 || height <= 0) return false

        val barLower = avoid == null || avoid.centerY() >= height / 2
        val rangeTop = if (barLower) height * 0.20f else height * 0.35f
        val rangeBottom = if (barLower) height * 0.70f else height * 0.85f
        val requested = height * fraction
        var distance = minOf(requested, rangeBottom - rangeTop)
        if (distance <= 0f) return false

        var top = if (barLower) rangeTop else rangeBottom - distance
        var bottom = top + distance

        if (avoid != null) {
            val aboveTop = rangeTop
            val aboveBottom = minOf(rangeBottom, avoid.top.toFloat() - 1f)
            val aboveRoom = aboveBottom - aboveTop
            val belowTop = maxOf(rangeTop, avoid.bottom.toFloat() + 1f)
            val belowBottom = rangeBottom
            val belowRoom = belowBottom - belowTop
            val useAbove = when {
                barLower && aboveRoom >= distance -> true
                !barLower && belowRoom >= distance -> false
                barLower && belowRoom >= distance -> false
                !barLower && aboveRoom >= distance -> true
                aboveRoom >= belowRoom -> true
                else -> false
            }
            if (useAbove) {
                distance = minOf(distance, aboveRoom)
                top = aboveTop
                bottom = aboveTop + distance
            } else {
                distance = minOf(distance, belowRoom)
                bottom = belowBottom
                top = belowBottom - distance
            }
            if (distance <= 0f || bottom <= top) return false
        }

        val centerX = width / 2f
        val startY = if (older) top else bottom
        val endY = if (older) bottom else top
        val path = Path().apply {
            moveTo(centerX, startY)
            lineTo(centerX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 350))
            .build()
        val dispatched = suspendCancellableCoroutine<Boolean> { continuation ->
            val callback = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(false)
                }
            }
            val sent = runCatching {
                svc.dispatchGesture(gesture, callback, null)
            }.getOrDefault(false)
            if (!sent && continuation.isActive) continuation.resume(false)
        }
        if (dispatched) delay(550)
        return dispatched
    }

    suspend fun locate(
        svc: AccessibilityService,
        date: LocalDate,
        time: String,
        timeExact: Boolean,
        avoid: () -> Rect?
    ): LocateResult {
        if (!isWeChatForeground(svc)) return LocateResult.FAILED
        val ready = waitFor(3000) {
            isWeChatForeground(svc) &&
                findNode(svc.rootInActiveWindow) {
                    (it.isEditable || it.isScrollable) && it.isVisibleToUser
                } != null
        }
        if (!ready || !isWeChatForeground(svc)) return LocateResult.FAILED

        // 阶段 1：向更早翻，直到看见目标日期之前的日期或到顶。
        var anySeparator = false
        var screens = 0
        var unchangedOlder = 0
        for (i in 0 until 80) {
            if (!isWeChatForeground(svc)) return LocateResult.FAILED
            val root = svc.rootInActiveWindow
            val separators = visibleSeparators(root)
            screens++
            if (separators.isNotEmpty()) anySeparator = true
            if (screens >= 4 && !anySeparator) return LocateResult.CANNOT_READ_TIME
            if (separators.any { it.first.toLocalDate() < date }) break
            val before = visibleTextSignature(root)
            if (!swipe(svc, older = true, fraction = 0.45f, avoid = avoid())) {
                return LocateResult.FAILED
            }
            if (!isWeChatForeground(svc)) return LocateResult.FAILED
            val after = visibleTextSignature(svc.rootInActiveWindow)
            if (after == before) unchangedOlder++ else unchangedOlder = 0
            if (unchangedOlder >= 2) break
        }
        if (!anySeparator) return LocateResult.CANNOT_READ_TIME

        // 阶段 2：向更新翻，收集目标日期的全部时间分隔。
        val collected = HashSet<LocalDateTime>()
        var unchangedNewer = 0
        for (i in 0 until 80) {
            if (!isWeChatForeground(svc)) return LocateResult.FAILED
            val root = svc.rootInActiveWindow
            val separators = visibleSeparators(root)
            separators.forEach {
                if (it.first.toLocalDate() == date) collected.add(it.first)
            }
            if (separators.any { it.first.toLocalDate() > date }) break
            val before = visibleTextSignature(root)
            if (!swipe(svc, older = false, fraction = 0.4f, avoid = avoid())) {
                return LocateResult.FAILED
            }
            if (!isWeChatForeground(svc)) return LocateResult.FAILED
            val after = visibleTextSignature(svc.rootInActiveWindow)
            if (after == before) unchangedNewer++ else unchangedNewer = 0
            if (unchangedNewer >= 2) break
        }

        val anchor = ChatTime.pickAnchor(collected.toList(), date, time, timeExact)
            ?: return LocateResult.NO_CHAT_THAT_DAY

        // 阶段 3：回到 anchor。
        var anchorRect: Rect? = null
        for (i in 0 until 60) {
            if (!isWeChatForeground(svc)) return LocateResult.FAILED
            val separators = visibleSeparators(svc.rootInActiveWindow)
            val hit = separators.firstOrNull { it.first == anchor }
            if (hit != null) {
                anchorRect = hit.second
                break
            }
            if (!swipe(svc, older = true, fraction = 0.3f, avoid = avoid())) {
                return LocateResult.FAILED
            }
            if (!isWeChatForeground(svc)) return LocateResult.FAILED
        }
        val rect = anchorRect ?: return LocateResult.FAILED

        val height = svc.resources.displayMetrics.heightPixels
        val top = rect.top.toFloat()
        if (height > 0 && top > height * 0.30f) {
            val fraction = (top - height * 0.18f) / height
            if (fraction > 0f) {
                if (!swipe(svc, older = false, fraction = fraction, avoid = avoid())) {
                    return LocateResult.FAILED
                }
                if (!isWeChatForeground(svc)) return LocateResult.FAILED
                val stillVisible = visibleSeparators(svc.rootInActiveWindow)
                    .any { it.first == anchor }
                if (!stillVisible) {
                    if (!swipe(svc, older = true, fraction = 0.15f, avoid = avoid())) {
                        return LocateResult.FAILED
                    }
                    if (!isWeChatForeground(svc)) return LocateResult.FAILED
                }
            }
        }
        return LocateResult.OK
    }

    /** findContacts 自己会从聊天页按返回找搜索入口，这里无需额外操作。 */
    suspend fun backToSearchOrHome(svc: AccessibilityService) {
        // 保留接口，方便自动流程在下一页前显式调用。
    }

    private fun findSearchPageEditor(svc: AccessibilityService): AccessibilityNodeInfo? {
        val root = svc.rootInActiveWindow ?: return null
        val hasCancel = findNode(root) { node ->
            node.isVisibleToUser && node.text?.toString()?.trim() == "取消"
        } != null
        if (!hasCancel) return null
        return findNode(root) { it.isEditable && it.isVisibleToUser }
    }

    private suspend fun waitForSearchPageEditor(svc: AccessibilityService): AccessibilityNodeInfo? {
        var editor: AccessibilityNodeInfo? = null
        waitFor(5000) {
            editor = findSearchPageEditor(svc)
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

    private fun collectSeparators(
        node: AccessibilityNodeInfo?,
        out: MutableList<Pair<LocalDateTime, Rect>>
    ) {
        if (node == null) return
        if (node.isVisibleToUser) {
            val text = node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                ?: node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            if (text != null) {
                val parsed = ChatTime.parse(text)
                if (parsed != null) {
                    val rect = Rect()
                    node.getBoundsInScreen(rect)
                    out.add(parsed to rect)
                }
            }
        }
        for (i in 0 until node.childCount) collectSeparators(node.getChild(i), out)
    }

    private fun visibleTextSignature(root: AccessibilityNodeInfo?): String {
        val texts = ArrayList<String>()
        collectTexts(root, texts)
        return texts.joinToString("\u0001")
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
}
