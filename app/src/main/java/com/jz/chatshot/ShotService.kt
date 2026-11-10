package com.jz.chatshot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.jz.chatshot.core.Archive
import com.jz.chatshot.core.Candidate
import com.jz.chatshot.core.MatchResult
import com.jz.chatshot.core.Matcher
import com.jz.chatshot.core.Status
import com.jz.chatshot.core.Student
import com.jz.chatshot.core.Verdict
import java.io.File
import java.io.FileOutputStream
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

private enum class AutoState { IDLE, RUNNING, PAUSED }

class ShotService : AccessibilityService() {

    companion object {
        var instance: ShotService? = null
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val monthDayFormatter = DateTimeFormatter.ofPattern("MM-dd")
    private val diagnosticTimeFormatter = DateTimeFormatter.ofPattern("HHmmss")

    private var lastToast: Toast? = null
    private var transientStatus: String? = null
    private val clearTransientRunnable = Runnable {
        transientStatus = null
        refreshBar()
    }

    private var windowManager: WindowManager? = null
    private var bar: LinearLayout? = null
    private var titleView: TextView? = null
    private var autoButton: Button? = null
    private var findButton: Button? = null
    private var candidateScroll: ScrollView? = null
    private var candidateContainer: LinearLayout? = null
    private var searchJob: Job? = null
    private var searchToken = 0
    private var lastShotAt = 0L

    private var autoState = AutoState.IDLE
    private var autoJob: Job? = null
    private var autoStep: String? = null
    private var autoPauseReason: String? = null
    private var autoNotice: String? = null
    private var autoCandidateStudentId: Int? = null
    private var pausedStudent: Student? = null
    private var pausedCandidates: List<Candidate> = emptyList()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Store.init(this)
    }

    override fun onDestroy() {
        instance = null
        searchJob?.cancel()
        hideBar()
        scope.cancel()
        handler.removeCallbacksAndMessages(null)
        ioExecutor.shutdown()
        super.onDestroy()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        hideBar()
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    fun showBar() {
        if (bar != null) {
            refreshBar()
            if (autoState == AutoState.PAUSED) renderPausedPanel()
            return
        }
        val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (wm == null) {
            toast("悬浮条添加失败，请检查悬浮窗权限")
            return
        }
        windowManager = wm

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#CC000000"))
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }

        val title = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(dp(4), dp(2), dp(4), dp(6))
        }
        titleView = title
        root.addView(
            title,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val labels = listOf("◀", "自动", "查找", "截图", "未找到", "▶", "✕")
        labels.forEachIndexed { index, label ->
            val button = Button(this).apply {
                text = label
                textSize = 13f
                isAllCaps = false
                isSingleLine = true
                minimumWidth = 0
                minWidth = 0
                minimumHeight = 0
                minHeight = 0
                setPadding(dp(2), dp(2), dp(2), dp(2))
                setTextColor(Color.WHITE)
                backgroundTintList = ColorStateList.valueOf(Color.parseColor("#33FFFFFF"))
                setOnClickListener { onBarAction(index) }
            }
            buttons.addView(
                button,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
        }
        autoButton = buttons.getChildAt(1) as Button
        findButton = buttons.getChildAt(2) as Button
        findButton?.setOnLongClickListener {
            saveDiagnostic()
            true
        }
        root.addView(
            buttons,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val candidates = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        candidateContainer = candidates
        val scroll = ScrollView(this).apply {
            visibility = View.GONE
            addView(candidates)
        }
        candidateScroll = scroll
        root.addView(
            scroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220))
        )

        val params = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(160)
            horizontalMargin = 0.02f
        }

        var startRawY = 0f
        var startY = 0
        root.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRawY = event.rawY
                    startY = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val maxY = resources.displayMetrics.heightPixels - dp(80)
                    params.y = (startY - (event.rawY - startRawY).toInt()).coerceIn(0, maxY)
                    runCatching { wm.updateViewLayout(root, params) }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }

        try {
            wm.addView(root, params)
        } catch (e: Exception) {
            toast("悬浮条添加失败，请检查悬浮窗权限")
            titleView = null
            autoButton = null
            findButton = null
            candidateScroll = null
            candidateContainer = null
            return
        }
        bar = root
        refreshBar()
        if (autoState == AutoState.PAUSED) renderPausedPanel()
    }

    fun hideBar() {
        stopAuto()
        searchJob?.cancel()
        clearTransientStatus()
        hideCandidates()
        val view = bar ?: return
        runCatching { windowManager?.removeView(view) }
        bar = null
        titleView = null
        autoButton = null
        findButton = null
        candidateScroll = null
        candidateContainer = null
    }

    private fun refreshBar() {
        autoButton?.text = when (autoState) {
            AutoState.IDLE -> "自动"
            AutoState.RUNNING -> "暂停"
            AutoState.PAUSED -> "继续"
        }
        val title = titleView ?: return
        val total = Store.students.size
        val base = if (total == 0) {
            "0/0 名单为空"
        } else {
            val index = Store.current.coerceIn(0, total - 1)
            val student = Store.students[index]
            val guardian = if (student.guardian.isEmpty()) "" else "（${student.guardian}）"
            "${index + 1}/$total ${student.grade} ${student.name}$guardian " +
                "${bannerDate(student)} 已截${student.shots.size}张"
        }
        val suffix = when (autoState) {
            AutoState.RUNNING -> autoStep ?: "运行中"
            AutoState.PAUSED -> null
            AutoState.IDLE -> autoNotice ?: transientStatus
        }
        title.text = if (suffix == null) base else "$base $suffix"
    }

    private fun hourLabel(time: String): String {
        val hour = time.substringBefore(':').trim().toIntOrNull() ?: return ""
        return if (hour in 0..23) "${hour}点" else ""
    }

    private fun bannerDate(student: Student): String {
        val from = student.dateFrom
        val to = student.dateTo
        val base = when {
            from != null && to != null && from == to -> monthDayFormatter.format(from)
            from != null && to != null ->
                "${monthDayFormatter.format(from)}~${monthDayFormatter.format(to)}"
            from != null -> monthDayFormatter.format(from)
            to != null -> monthDayFormatter.format(to)
            else -> "无日期"
        }
        val time = hourLabel(student.time)
        return if (time.isEmpty()) base else "$base $time"
    }

    private fun onBarAction(index: Int) {
        when (index) {
            0 -> if (autoState == AutoState.IDLE) move(-1)
            1 -> onAutoButton()
            2 -> when (autoState) {
                AutoState.IDLE -> startFind()
                AutoState.PAUSED -> retryCurrent()
                AutoState.RUNNING -> Unit
            }
            3 -> if (autoState != AutoState.RUNNING) onManualShoot()
            4 -> if (autoState != AutoState.RUNNING) markNotFound()
            5 -> if (autoState == AutoState.IDLE) move(1)
            6 -> hideBar()
        }
    }

    private fun onAutoButton() {
        when (autoState) {
            AutoState.IDLE -> startAuto()
            AutoState.RUNNING -> pauseAutoManually()
            AutoState.PAUSED -> continueAuto()
        }
    }

    private fun currentStudent(): Student? {
        val total = Store.students.size
        if (total == 0) return null
        val index = Store.current.coerceIn(0, total - 1)
        if (index != Store.current) Store.current = index
        return Store.students[index]
    }

    private fun move(delta: Int) {
        if (autoState != AutoState.IDLE) return
        val total = Store.students.size
        if (total == 0) return
        Store.current = (Store.current + delta).coerceIn(0, total - 1)
        Store.save()
        autoNotice = null
        clearTransientStatus()
        hideCandidates()
        refreshBar()
    }

    private fun markNotFound() {
        if (autoState == AutoState.RUNNING) return
        val student = currentStudent() ?: return
        Store.update(student.id) { it.copy(status = Status.NOT_FOUND) }
        if (autoState == AutoState.PAUSED) {
            hideCandidates()
            autoCandidateStudentId = null
            continueAuto()
        } else {
            autoNotice = null
            move(1)
        }
    }

    private fun onManualShoot() {
        if (autoState == AutoState.RUNNING) return
        val student = currentStudent() ?: run {
            if (autoState == AutoState.IDLE) showTransientStatus("请先导入名单")
            return
        }
        if (autoState == AutoState.IDLE) {
            autoNotice = null
            refreshBar()
        }
        scope.launch {
            val ok = shoot(student)
            if (ok && autoState == AutoState.IDLE) {
                showTransientStatus("✓已保存")
            }
        }
    }

    private suspend fun shoot(student: Student): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastShotAt < 1100) return false
        lastShotAt = now

        lastToast?.cancel()
        lastToast = null
        val visibleDate = WeChat.visibleDate(rootInActiveWindow)
        val date = Archive.pickShotDate(student.dateFrom, student.dateTo, visibleDate)
        val barView = bar
        barView?.visibility = View.INVISIBLE

        var restored = false
        fun restoreBar() {
            if (!restored) {
                restored = true
                barView?.visibility = View.VISIBLE
                refreshBar()
            }
        }

        try {
            delay(250)
            return suspendCancellableCoroutine<Boolean> { continuation ->
                val finish: (Boolean) -> Unit = { result ->
                    restoreBar()
                    if (continuation.isActive) continuation.resume(result)
                }

                continuation.invokeOnCancellation {
                    handler.post { restoreBar() }
                }

                val callback = object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        val buffer = screenshot.hardwareBuffer
                        val bitmap = runCatching {
                            Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                                ?.copy(Bitmap.Config.ARGB_8888, false)
                        }.getOrNull()
                        buffer.close()
                        if (bitmap == null) {
                            toast("截图失败：无法生成图片")
                            finish(false)
                            return
                        }

                        val latest = Store.students.firstOrNull { it.id == student.id } ?: student
                        val path = Archive.shotPath(latest.grade, latest.name, date, latest.shots)
                        ioExecutor.execute {
                            val saved = runCatching {
                                val file = File(Store.shotsRoot(), path)
                                file.parentFile?.mkdirs()
                                FileOutputStream(file).use { out ->
                                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                                }
                            }.getOrDefault(false)
                            bitmap.recycle()
                            handler.post {
                                if (saved) {
                                    Store.update(student.id) {
                                        it.copy(shots = it.shots + path, status = Status.SHOT)
                                    }
                                } else {
                                    toast("保存截图失败")
                                }
                                finish(saved)
                            }
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        toast("截图失败：错误码 $errorCode")
                        finish(false)
                    }
                }
                try {
                    takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, callback)
                } catch (e: Exception) {
                    toast("截图失败：${e.message ?: "未知错误"}")
                    finish(false)
                }
            }
        } catch (e: CancellationException) {
            restoreBar()
            throw e
        }
    }

    private fun startFind() {
        if (autoState != AutoState.IDLE) return
        val student = currentStudent() ?: run {
            showTransientStatus("请先导入名单")
            return
        }
        autoNotice = null
        searchJob?.cancel()
        hideCandidates()
        searchToken++
        val token = searchToken
        findButton?.apply {
            text = "…"
            isEnabled = false
        }
        refreshBar()
        searchJob = scope.launch {
            try {
                val contacts = searchContacts(student)
                if (contacts.isEmpty()) {
                    showTransientStatus("未找到候选，请手动查找")
                    return@launch
                }
                val result = matchContacts(student, contacts)
                when (result.verdict) {
                    Verdict.AUTO -> {
                        val candidate = result.candidates.firstOrNull()
                        if (candidate != null) {
                            openCandidateManual(student, candidate)
                        } else {
                            showTransientStatus("未找到候选，请手动查找")
                        }
                    }
                    Verdict.CONFIRM -> showCandidates(student, result.candidates)
                    Verdict.NONE -> showTransientStatus("未找到候选，请手动查找")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showTransientStatus("自动查找失败，请手动操作")
            } finally {
                if (token == searchToken) {
                    findButton?.apply {
                        text = "查找"
                        isEnabled = true
                    }
                }
            }
        }
    }

    private suspend fun searchContacts(student: Student): List<String> {
        var contacts = emptyList<String>()
        for (term in Matcher.searchTerms(student.name)) {
            contacts = WeChat.findContacts(this@ShotService, term)
            if (contacts.isNotEmpty()) break
        }
        return contacts
    }

    private fun matchContacts(student: Student, contacts: List<String>): MatchResult {
        val otherNames = Store.students.filter { it.id != student.id }.map { it.name }
        val remembered = Store.memory[Store.memKey(student)]
        val sameNameExists = Store.students.any {
            it.id != student.id && it.name == student.name
        }
        val usedContacts = Store.students
            .filter { it.id != student.id && it.contact.isNotBlank() }
            .associate { it.contact to "${it.grade} ${it.name}".trim() }
        return Matcher.match(
            student = student,
            contacts = contacts,
            otherNames = otherNames,
            remembered = remembered,
            sameNameExists = sameNameExists,
            usedContacts = usedContacts
        )
    }

    private suspend fun openAndRecord(student: Student, candidate: Candidate): Boolean {
        val opened = try {
            WeChat.openContact(this@ShotService, candidate.contact)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        if (!opened) return false
        Store.update(student.id) {
            it.copy(status = Status.MATCHED, contact = candidate.contact)
        }
        Store.memory[Store.memKey(student)] = candidate.contact
        Store.save()
        return true
    }

    private suspend fun openCandidateManual(student: Student, candidate: Candidate) {
        if (!openAndRecord(student, candidate)) {
            showTransientStatus("自动查找失败，请手动操作")
            return
        }
        hideCandidates()
        showTransientStatus("✓已打开 ${candidate.contact}")
    }

    private fun startAuto() {
        if (autoState != AutoState.IDLE) return
        if (Store.students.isEmpty()) {
            showTransientStatus("请先导入名单")
            return
        }
        val start = Store.current.coerceIn(0, Store.students.lastIndex)
        searchJob?.cancel()
        searchToken++
        findButton?.apply {
            text = "查找"
            isEnabled = true
        }
        hideCandidates()
        clearTransientStatus()
        autoNotice = null
        autoPauseReason = null
        autoStep = null
        autoCandidateStudentId = null
        pausedStudent = null
        pausedCandidates = emptyList()
        autoState = AutoState.RUNNING
        Store.current = start
        Store.save()
        refreshBar()
        launchAutoLoop(start, skipFindForFirst = false)
    }

    private fun pauseAutoManually() {
        if (autoState != AutoState.RUNNING) return
        autoJob?.cancel()
        autoJob = null
        autoState = AutoState.PAUSED
        autoStep = null
        autoPauseReason = "已暂停"
        autoCandidateStudentId = null
        pausedStudent = currentStudent()
        pausedCandidates = emptyList()
        findButton?.apply {
            text = "查找"
            isEnabled = true
        }
        clearTransientStatus()
        refreshBar()
        renderPausedPanel()
    }

    private fun continueAuto() {
        if (autoState != AutoState.PAUSED) return
        hideCandidates()
        autoCandidateStudentId = null
        pausedStudent = null
        pausedCandidates = emptyList()
        autoPauseReason = null
        autoStep = null
        autoNotice = null
        clearTransientStatus()
        val total = Store.students.size
        val next = Store.current + 1
        if (total == 0 || next >= total) {
            finishAuto()
            return
        }
        Store.current = next
        Store.save()
        autoState = AutoState.RUNNING
        refreshBar()
        launchAutoLoop(next, skipFindForFirst = false)
    }

    private fun resumeAutoAtLocate(studentId: Int) {
        val index = Store.students.indexOfFirst { it.id == studentId }
        if (index < 0) {
            finishAuto()
            return
        }
        hideCandidates()
        autoCandidateStudentId = null
        pausedStudent = null
        pausedCandidates = emptyList()
        autoPauseReason = null
        autoStep = null
        autoNotice = null
        clearTransientStatus()
        Store.current = index
        Store.save()
        autoState = AutoState.RUNNING
        refreshBar()
        launchAutoLoop(index, skipFindForFirst = true, forceFirst = true)
    }

    private fun launchAutoLoop(
        startIndex: Int,
        skipFindForFirst: Boolean,
        skipLocateForFirst: Boolean = false,
        forceFirst: Boolean = false
    ) {
        autoJob?.cancel()
        autoJob = scope.launch {
            runAutoLoop(startIndex, skipFindForFirst, skipLocateForFirst, forceFirst)
        }
    }

    private suspend fun runAutoLoop(
        startIndex: Int,
        skipFindForFirst: Boolean,
        skipLocateForFirst: Boolean,
        forceFirst: Boolean
    ) {
        var index = startIndex.coerceAtLeast(0)
        var skipFindForIndex = if (skipFindForFirst) startIndex else -1
        var skipLocateForIndex = if (skipLocateForFirst) startIndex else -1
        try {
            while (index < Store.students.size) {
                if (autoState != AutoState.RUNNING) return
                val student = Store.students.getOrNull(index) ?: break
                Store.current = index
                Store.save()
                refreshBar()
                val terminal = student.status == Status.SHOT ||
                    student.status == Status.NOT_FOUND ||
                    student.status == Status.SKIPPED
                val forcedFirst = forceFirst && index == startIndex
                if (terminal && !forcedFirst) {
                    index++
                    continue
                }

                val skipFind = index == skipFindForIndex
                skipFindForIndex = -1
                if (!skipFind) {
                    setAutoStep("查找中")
                    delay(700)
                    val contacts = searchContacts(student)
                    if (contacts.isEmpty()) {
                        pauseAuto("未找到联系人")
                        return
                    }
                    val result = matchContacts(student, contacts)
                    when (result.verdict) {
                        Verdict.AUTO -> {
                            val candidate = result.candidates.firstOrNull()
                            if (candidate == null) {
                                pauseAuto("未找到联系人")
                                return
                            }
                            if (!openAndRecord(student, candidate)) {
                                pauseAuto("打开聊天失败")
                                return
                            }
                            hideCandidates()
                            delay(700)
                        }
                        Verdict.CONFIRM -> {
                            pausedStudent = student
                            pausedCandidates = result.candidates
                            autoCandidateStudentId = student.id
                            pauseAuto(
                                "有 ${result.candidates.size} 位候选，请选择",
                                keepCandidates = true
                            )
                            return
                        }
                        Verdict.NONE -> {
                            pauseAuto("未找到联系人")
                            return
                        }
                    }
                }

                val skipLocate = index == skipLocateForIndex
                skipLocateForIndex = -1
                if (!skipLocate) {
                    val date = student.dateFrom
                    if (date == null) {
                        pauseAuto("名单没有日期，请手动翻到位置")
                        return
                    }
                    setAutoStep("定位中")
                    delay(700)
                    val locateResult = try {
                        WeChat.locate(
                            this@ShotService,
                            date,
                            student.time,
                            student.timeExact
                        ) { barRect() }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        LocateResult.FAILED
                    }
                    when (locateResult) {
                        LocateResult.OK -> Unit
                        LocateResult.NO_CHAT_THAT_DAY -> {
                            pauseAuto("${monthDayFormatter.format(date)} 当天没有聊天记录")
                            return
                        }
                        LocateResult.CANNOT_READ_TIME -> {
                            saveDiagnostic()
                            pauseAuto("读不到聊天时间，请手动翻到位置")
                            return
                        }
                        LocateResult.FAILED -> {
                            pauseAuto("定位失败，请手动翻到位置")
                            return
                        }
                    }
                }

                setAutoStep("截图中")
                delay(700)
                if (!shoot(student)) {
                    pauseAuto("截图失败")
                    return
                }

                index++
                if (index < Store.students.size) {
                    Store.current = index
                    Store.save()
                }
                refreshBar()
                delay(800)
            }
            finishAuto()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            pauseAuto("自动流程异常：${e.message ?: "未知错误"}")
        }
    }

    private fun setAutoStep(step: String) {
        autoStep = step
        refreshBar()
    }

    private fun pauseAuto(reason: String, keepCandidates: Boolean = false) {
        autoState = AutoState.PAUSED
        autoStep = null
        autoPauseReason = reason
        if (!keepCandidates) {
            pausedStudent = currentStudent()
            pausedCandidates = emptyList()
            autoCandidateStudentId = null
        } else if (pausedStudent == null) {
            pausedStudent = currentStudent()
        }
        findButton?.apply {
            text = "查找"
            isEnabled = true
        }
        refreshBar()
        renderPausedPanel()
    }

    private fun renderPausedPanel() {
        val container = candidateContainer ?: return
        val scroll = candidateScroll ?: return
        container.removeAllViews()
        addPauseRow(
            container,
            autoPauseReason ?: "已暂停",
            Color.parseColor("#FF9800"),
            13f
        )
        val student = pausedStudent
        if (student != null && pausedCandidates.isNotEmpty()) {
            for (candidate in pausedCandidates) {
                addCandidateRow(container, student, candidate, autoResume = true)
            }
            addPauseRow(
                container,
                "都不是",
                Color.parseColor("#FFCC80"),
                onClick = { dismissAutoCandidates() }
            )
        }
        addPauseRow(
            container,
            "重试本人",
            Color.parseColor("#FFCC80"),
            onClick = { retryCurrent() }
        )
        addPauseRow(
            container,
            "我已打开聊天，帮我定位并截图",
            Color.parseColor("#FFCC80"),
            onClick = { locateShotCurrent() }
        )
        addPauseRow(
            container,
            "就截当前屏幕，然后下一位",
            Color.parseColor("#FFCC80"),
            onClick = { shotOnlyCurrent() }
        )
        scroll.visibility = View.VISIBLE
    }

    private fun dismissAutoCandidates() {
        if (autoState == AutoState.PAUSED && autoCandidateStudentId != null) {
            autoCandidateStudentId = null
            pausedCandidates = emptyList()
            pausedStudent = currentStudent()
            autoPauseReason = "请手动处理"
            refreshBar()
            renderPausedPanel()
        } else {
            hideCandidates()
        }
    }

    private fun retryCurrent() {
        val index = beginPausedAction() ?: return
        launchAutoLoop(
            index,
            skipFindForFirst = false,
            skipLocateForFirst = false,
            forceFirst = true
        )
    }

    private fun locateShotCurrent() {
        val index = beginPausedAction() ?: return
        launchAutoLoop(
            index,
            skipFindForFirst = true,
            skipLocateForFirst = false,
            forceFirst = true
        )
    }

    private fun shotOnlyCurrent() {
        val index = beginPausedAction() ?: return
        launchAutoLoop(
            index,
            skipFindForFirst = true,
            skipLocateForFirst = true,
            forceFirst = true
        )
    }

    private fun beginPausedAction(): Int? {
        if (autoState != AutoState.PAUSED) return null
        if (Store.students.isEmpty()) {
            toast("请先导入名单")
            return null
        }
        val index = Store.current.coerceIn(0, Store.students.lastIndex)
        Store.current = index
        Store.save()
        hideCandidates()
        pausedStudent = null
        pausedCandidates = emptyList()
        autoCandidateStudentId = null
        autoPauseReason = null
        autoStep = null
        autoNotice = null
        clearTransientStatus()
        autoState = AutoState.RUNNING
        refreshBar()
        return index
    }

    private fun selectAutoCandidate(student: Student, candidate: Candidate) {
        if (autoState != AutoState.PAUSED || autoCandidateStudentId != student.id) return
        scope.launch {
            val opened = openAndRecord(student, candidate)
            if (!opened) {
                if (autoState == AutoState.PAUSED) {
                    autoPauseReason = "打开聊天失败"
                    refreshBar()
                    renderPausedPanel()
                }
                return@launch
            }
            if (autoState != AutoState.PAUSED || autoCandidateStudentId != student.id) {
                return@launch
            }
            resumeAutoAtLocate(student.id)
        }
    }

    private fun finishAuto() {
        autoState = AutoState.IDLE
        autoStep = null
        autoPauseReason = null
        autoCandidateStudentId = null
        pausedStudent = null
        pausedCandidates = emptyList()
        autoJob = null
        hideCandidates()
        clearTransientStatus()
        val shot = Store.students.count { it.status == Status.SHOT }
        val unfinished = Store.students.count {
            it.status == Status.PENDING || it.status == Status.MATCHED
        }
        autoNotice = "全部完成：已截 $shot 人，未完成 $unfinished 人"
        refreshBar()
    }

    private fun stopAuto() {
        autoJob?.cancel()
        autoJob = null
        autoState = AutoState.IDLE
        autoStep = null
        autoPauseReason = null
        autoNotice = null
        autoCandidateStudentId = null
        pausedStudent = null
        pausedCandidates = emptyList()
    }

    private fun showCandidates(
        student: Student,
        candidates: List<Candidate>,
        autoResume: Boolean = false
    ) {
        val container = candidateContainer ?: return
        val scroll = candidateScroll ?: return
        container.removeAllViews()
        if (candidates.isEmpty()) {
            scroll.visibility = View.GONE
            return
        }
        for (candidate in candidates) {
            addCandidateRow(container, student, candidate, autoResume)
        }
        addPauseRow(
            container,
            "都不是",
            Color.parseColor("#FFCC80"),
            onClick = { if (autoResume) dismissAutoCandidates() else hideCandidates() }
        )
        scroll.visibility = View.VISIBLE
    }

    private fun addCandidateRow(
        container: LinearLayout,
        student: Student,
        candidate: Candidate,
        autoResume: Boolean
    ) {
        val row = TextView(this).apply {
            text = "${candidate.contact}  ${candidate.score}分\n" +
                candidate.reasons.joinToString("；")
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setBackgroundColor(Color.parseColor("#33FFFFFF"))
            isClickable = true
            setOnClickListener {
                if (autoResume) {
                    selectAutoCandidate(student, candidate)
                } else {
                    scope.launch { openCandidateManual(student, candidate) }
                }
            }
        }
        container.addView(
            row,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(4) }
        )
    }

    private fun addPauseRow(
        container: LinearLayout,
        text: String,
        color: Int,
        textSize: Float = 12f,
        onClick: (() -> Unit)? = null
    ) {
        val action = onClick
        val row = TextView(this).apply {
            this.text = text
            setTextColor(color)
            this.textSize = textSize
            setPadding(dp(8), dp(6), dp(8), dp(6))
            if (action != null) {
                isClickable = true
                setOnClickListener { action() }
            }
        }
        container.addView(
            row,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }

    private fun hideCandidates() {
        candidateContainer?.removeAllViews()
        candidateScroll?.visibility = View.GONE
    }

    private fun showTransientStatus(message: String) {
        autoNotice = null
        handler.removeCallbacks(clearTransientRunnable)
        transientStatus = message
        refreshBar()
        handler.postDelayed(clearTransientRunnable, 2000)
    }

    private fun clearTransientStatus() {
        handler.removeCallbacks(clearTransientRunnable)
        transientStatus = null
    }

    private fun saveDiagnostic() {
        val snapshot = runCatching { WeChat.dump(rootInActiveWindow) }.getOrNull()
        if (snapshot == null) {
            toast("保存诊断失败")
            return
        }
        val stamp = diagnosticTimeFormatter.format(LocalTime.now())
        ioExecutor.execute {
            val saved = runCatching {
                val dir = Store.zipDir()
                dir.mkdirs()
                File(dir, "诊断_$stamp.txt").writeText(snapshot, Charsets.UTF_8)
            }.isSuccess
            handler.post {
                if (saved) {
                    showTransientStatus("✓已保存诊断")
                } else {
                    toast("保存诊断失败")
                }
            }
        }
    }

    private fun toast(message: String) {
        val tip = Toast.makeText(this, message, Toast.LENGTH_SHORT)
        lastToast = tip
        tip.show()
    }

    private fun barRect(): Rect? {
        val view = bar ?: return null
        if (view.width <= 0 || view.height <= 0) return null
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return Rect(
            location[0],
            location[1],
            location[0] + view.width,
            location[1] + view.height
        )
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
