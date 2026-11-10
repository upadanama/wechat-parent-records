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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

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
    private var findButton: Button? = null
    private var candidateScroll: ScrollView? = null
    private var candidateContainer: LinearLayout? = null
    private var searchJob: Job? = null
    private var searchToken = 0
    private var lastShotAt = 0L

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
        val labels = listOf("◀", "查找", "截图", "未找到", "▶", "✕")
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
        findButton = buttons.getChildAt(1) as Button
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
            findButton = null
            candidateScroll = null
            candidateContainer = null
            return
        }
        bar = root
        refreshBar()
    }

    fun hideBar() {
        searchJob?.cancel()
        clearTransientStatus()
        hideCandidates()
        val view = bar ?: return
        runCatching { windowManager?.removeView(view) }
        bar = null
        titleView = null
        findButton = null
        candidateScroll = null
        candidateContainer = null
    }

    private fun refreshBar() {
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
        val status = transientStatus
        title.text = if (status == null) base else "$base $status"
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
        return if (student.time.isNotEmpty()) "$base ${student.time}" else base
    }

    private fun onBarAction(index: Int) {
        when (index) {
            0 -> move(-1)
            1 -> startFind()
            2 -> takeShot()
            3 -> markNotFound()
            4 -> move(1)
            5 -> hideBar()
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
        val total = Store.students.size
        if (total == 0) return
        Store.current = (Store.current + delta).coerceIn(0, total - 1)
        Store.save()
        clearTransientStatus()
        hideCandidates()
        refreshBar()
    }

    private fun markNotFound() {
        val student = currentStudent() ?: return
        Store.update(student.id) { it.copy(status = Status.NOT_FOUND) }
        move(1)
    }

    private fun takeShot() {
        val student = currentStudent() ?: run {
            showTransientStatus("请先导入名单")
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastShotAt < 1100) return
        lastShotAt = now

        lastToast?.cancel()
        lastToast = null
        val visibleDate = WeChat.visibleDate(rootInActiveWindow)
        val date = Archive.pickShotDate(student.dateFrom, student.dateTo, visibleDate)
        val barView = bar
        barView?.visibility = View.INVISIBLE

        handler.postDelayed({
            try {
                takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        val buffer = screenshot.hardwareBuffer
                        val bitmap = runCatching {
                            Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                                ?.copy(Bitmap.Config.ARGB_8888, false)
                        }.getOrNull()
                        buffer.close()
                        if (bitmap == null) {
                            barView?.visibility = View.VISIBLE
                            toast("截图失败：无法生成图片")
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
                                    barView?.visibility = View.VISIBLE
                                    showTransientStatus("✓已保存")
                                } else {
                                    barView?.visibility = View.VISIBLE
                                    refreshBar()
                                    toast("保存截图失败")
                                }
                            }
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        barView?.visibility = View.VISIBLE
                        toast("截图失败：错误码 $errorCode")
                    }
                })
            } catch (e: Exception) {
                barView?.visibility = View.VISIBLE
                toast("截图失败：${e.message ?: "未知错误"}")
            }
        }, 250)
    }

    private fun startFind() {
        val student = currentStudent() ?: run {
            showTransientStatus("请先导入名单")
            return
        }
        searchJob?.cancel()
        hideCandidates()
        searchToken++
        val token = searchToken
        findButton?.apply {
            text = "…"
            isEnabled = false
        }
        searchJob = scope.launch {
            try {
                var contacts = emptyList<String>()
                for (term in Matcher.searchTerms(student.name)) {
                    contacts = WeChat.findContacts(this@ShotService, term)
                    if (contacts.isNotEmpty()) break
                }
                if (contacts.isEmpty()) {
                    showTransientStatus("未找到候选，请手动查找")
                    return@launch
                }
                val otherNames = Store.students.filter { it.id != student.id }.map { it.name }
                val remembered = Store.memory[Store.memKey(student)]
                val sameNameExists = Store.students.any {
                    it.id != student.id && it.name == student.name
                }
                val usedContacts = Store.students
                    .filter { it.id != student.id && it.contact.isNotBlank() }
                    .associate { it.contact to "${it.grade} ${it.name}".trim() }
                val result = Matcher.match(
                    student = student,
                    contacts = contacts,
                    otherNames = otherNames,
                    remembered = remembered,
                    sameNameExists = sameNameExists,
                    usedContacts = usedContacts
                )
                when (result.verdict) {
                    Verdict.AUTO -> {
                        val candidate = result.candidates.firstOrNull()
                        if (candidate != null) {
                            openCandidate(student, candidate)
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

    private suspend fun openCandidate(student: Student, candidate: Candidate) {
        val opened = try {
            WeChat.openContact(this@ShotService, candidate.contact)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        if (!opened) {
            showTransientStatus("自动查找失败，请手动操作")
            return
        }
        Store.update(student.id) {
            it.copy(status = Status.MATCHED, contact = candidate.contact)
        }
        Store.memory[Store.memKey(student)] = candidate.contact
        Store.save()
        hideCandidates()
        showTransientStatus("✓已打开 ${candidate.contact}")
    }

    private fun showCandidates(student: Student, candidates: List<Candidate>) {
        val container = candidateContainer ?: return
        val scroll = candidateScroll ?: return
        container.removeAllViews()
        if (candidates.isEmpty()) {
            scroll.visibility = View.GONE
            return
        }
        for (candidate in candidates) {
            val row = TextView(this).apply {
                text = "${candidate.contact}  ${candidate.score}分\n" +
                    candidate.reasons.joinToString("；")
                setTextColor(Color.WHITE)
                textSize = 12f
                setPadding(dp(8), dp(6), dp(8), dp(6))
                setBackgroundColor(Color.parseColor("#33FFFFFF"))
                isClickable = true
                setOnClickListener {
                    scope.launch { openCandidate(student, candidate) }
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
        val none = TextView(this).apply {
            text = "都不是"
            setTextColor(Color.parseColor("#FFCC80"))
            textSize = 12f
            setPadding(dp(8), dp(6), dp(8), dp(6))
            isClickable = true
            setOnClickListener { hideCandidates() }
        }
        container.addView(
            none,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        scroll.visibility = View.VISIBLE
    }

    private fun hideCandidates() {
        candidateContainer?.removeAllViews()
        candidateScroll?.visibility = View.GONE
    }

    private fun showTransientStatus(message: String) {
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
