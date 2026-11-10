package com.jz.chatshot.core

import java.time.LocalDate
import java.util.Locale

object Roster {

    private val GUARDIAN_KEYS = listOf("监护人", "称呼", "沟通对象", "家长", "关系")
    private val DATE_KEYS = listOf("日期", "时间")
    private val TIME_TOKEN = Regex("""^(\d{1,2}):(\d{2})$""")
    private val FULL_DATE = Regex("""^(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})$""")
    private val CN_DATE = Regex("""^(\d{4})年(\d{1,2})月(\d{1,2})[日号]?$""")
    private val COMPACT_DATE = Regex("""^(\d{8})$""")
    private val NUMBER = Regex("""^(\d+)(?:\.0+)?$""")
    private val CN_MONTH_DAY = Regex("""^(\d{1,2})月(\d{1,2})[日号]?$""")
    private val SHORT_MONTH_DAY = Regex("""^(\d{1,2})[-/.](\d{1,2})$""")
    private val CHINESE_DIGITS = "一二三四五六七八九"
    private val DIGIT_TO_CHINESE = mapOf(
        '1' to '一', '2' to '二', '3' to '三', '4' to '四', '5' to '五',
        '6' to '六', '7' to '七', '8' to '八', '9' to '九'
    )
    private val RANGE_SEPARATORS = listOf("~", "至", "到")

    private data class Columns(
        val grade: Int,
        val name: Int,
        val guardian: Int,
        val dateFrom: Int,
        val dateTo: Int,
        val date: Int,
        val deviation: Int
    )

    fun parse(
        rows: List<List<String>>,
        defaultFrom: LocalDate? = null,
        defaultTo: LocalDate? = null,
        today: LocalDate = LocalDate.now()
    ): RosterResult {
        val issues = ArrayList<RosterIssue>()
        val header = findHeader(rows)
            ?: return RosterResult(
                emptyList(),
                listOf(RosterIssue(0, "未找到表头（需要包含 年级 和 姓名 列）"))
            )
        val (headerIndex, cols) = header
        val students = ArrayList<Student>()
        val firstRows = HashMap<String, Int>()

        for (r in headerIndex + 1 until rows.size) {
            val row = rows[r]
            val gradeRaw = cell(row, cols.grade)
            val nameRaw = cell(row, cols.name)
            if (gradeRaw.isBlank() && nameRaw.isBlank()) continue
            if (gradeRaw.isBlank() || nameRaw.isBlank()) {
                issues.add(RosterIssue(r + 1, "缺少年级/姓名"))
                continue
            }

            val grade = normGrade(gradeRaw)
            val name = nameRaw.trim()
            val guardian = cell(row, cols.guardian)
            var time = ""
            var from: LocalDate? = null
            var to: LocalDate? = null
            var dateInvalid = false

            if (cols.dateFrom >= 0 && cols.dateTo >= 0) {
                val rawFrom = cell(row, cols.dateFrom)
                val rawTo = cell(row, cols.dateTo)
                val (fromText, fromTime) = splitTime(rawFrom)
                val (toText, toTime) = splitTime(rawTo)
                time = fromTime.ifEmpty { toTime }

                var parsedFrom: LocalDate? = null
                var parsedTo: LocalDate? = null
                if (fromText.isNotBlank()) {
                    parsedFrom = parseDate(fromText, today)
                    if (parsedFrom == null) {
                        issues.add(RosterIssue(r + 1, "日期无法识别：$rawFrom"))
                        dateInvalid = true
                    }
                }
                if (toText.isNotBlank()) {
                    parsedTo = parseDate(toText, today)
                    if (parsedTo == null) {
                        issues.add(RosterIssue(r + 1, "日期无法识别：$rawTo"))
                        dateInvalid = true
                    }
                }
                if (!dateInvalid && (parsedFrom != null || parsedTo != null)) {
                    from = parsedFrom ?: parsedTo
                    to = parsedTo ?: parsedFrom
                }
            } else if (cols.date >= 0) {
                val raw = cell(row, cols.date)
                if (raw.isNotBlank()) {
                    val (dateText, parsedTime) = splitTime(raw)
                    time = parsedTime
                    val bounds = parseDateCell(dateText, today)
                    if (bounds == null) {
                        issues.add(RosterIssue(r + 1, "日期无法识别：$raw"))
                        dateInvalid = true
                    } else {
                        from = bounds.first
                        to = bounds.second
                    }
                }
            }

            var deviation = 0
            if (cols.deviation >= 0) {
                val devText = cell(row, cols.deviation)
                if (devText.isNotEmpty()) {
                    deviation = devText.toIntOrNull() ?: run {
                        issues.add(RosterIssue(r + 1, "偏差无法识别：$devText"))
                        0
                    }
                }
            }

            if (!dateInvalid && from != null && to != null) {
                from = from.minusDays(deviation.toLong())
                to = to.plusDays(deviation.toLong())
                if (from.isAfter(to)) {
                    val tmp = from
                    from = to
                    to = tmp
                }
            } else {
                from = defaultFrom
                to = defaultTo
                if (from == null && to != null) from = to
                if (to == null && from != null) to = from
            }

            val key = "$grade|$name|$guardian"
            val firstRow = firstRows[key]
            if (firstRow != null) {
                issues.add(RosterIssue(r + 1, "与第 $firstRow 行重复"))
            } else {
                firstRows[key] = r + 1
            }

            students.add(
                Student(
                    id = students.size,
                    grade = grade,
                    name = name,
                    guardian = guardian,
                    dateFrom = from,
                    dateTo = to,
                    time = time
                )
            )
        }
        return RosterResult(students, issues)
    }

    fun parseDate(s: String, today: LocalDate = LocalDate.now()): LocalDate? {
        val normalized = s.trim().replace('：', ':').replace('　', ' ')
        if (normalized.isEmpty()) return null
        val text = removeTimeTokens(normalized)
        if (text.isEmpty()) return null

        CN_DATE.matchEntire(text)?.let { m ->
            return safeDate(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
        }
        FULL_DATE.matchEntire(text)?.let { m ->
            return safeDate(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
        }
        COMPACT_DATE.matchEntire(text)?.let { m ->
            val digits = m.groupValues[1]
            return safeDate(digits.substring(0, 4).toInt(), digits.substring(4, 6).toInt(), digits.substring(6, 8).toInt())
        }
        NUMBER.matchEntire(text)?.let { m ->
            val value = m.groupValues[1].toIntOrNull() ?: return null
            if (value in 20000..80000) {
                return LocalDate.of(1899, 12, 30).plusDays(value.toLong())
            }
        }
        CN_MONTH_DAY.matchEntire(text)?.let { m ->
            return monthDay(m.groupValues[1].toInt(), m.groupValues[2].toInt(), today)
        }
        SHORT_MONTH_DAY.matchEntire(text)?.let { m ->
            return monthDay(m.groupValues[1].toInt(), m.groupValues[2].toInt(), today)
        }
        return null
    }

    fun normGrade(s: String): String {
        val t = s.filterNot { it.isWhitespace() }
        if (t.isEmpty()) return ""
        for (g in listOf("初一", "初二", "初三", "高一", "高二", "高三")) {
            if (t.startsWith(g)) return g
        }
        val first = t[0]
        if (t.length >= 3 && first in CHINESE_DIGITS && t.startsWith("${first}年级")) {
            return "${first}年级"
        }
        if (t.length >= 3 && first in '1'..'9' && t.startsWith("${first}年级")) {
            return "${DIGIT_TO_CHINESE[first]}年级"
        }
        if (t.length >= 2 && first in CHINESE_DIGITS &&
            (t[1] == '（' || t[1] == '(' || t[1] == '班' || t[1].isDigit())
        ) {
            return "${first}年级"
        }
        if (t.length >= 2 && first in '1'..'9' &&
            (t[1] == '（' || t[1] == '(' || t[1] == '班')
        ) {
            return "${DIGIT_TO_CHINESE[first]}年级"
        }
        if (t.length == 1) {
            if (first in '1'..'9') return "${DIGIT_TO_CHINESE[first]}年级"
            if (first in CHINESE_DIGITS) return "${first}年级"
        }
        return t
    }

    private fun findHeader(rows: List<List<String>>): Pair<Int, Columns>? {
        val limit = minOf(10, rows.size)
        for (i in 0 until limit) {
            val row = rows[i]
            val name = row.indexOfFirst { it.contains("姓名") }.takeIf { it >= 0 }
                ?: row.indexOfFirst { it.contains("学生") }.takeIf { it >= 0 }
                ?: continue
            val grade = row.indexOfFirst { it.contains("年级") }
            if (grade < 0 || grade == name) continue

            val guardian = row.indices.firstOrNull { idx ->
                idx != grade && idx != name && GUARDIAN_KEYS.any { row[idx].contains(it) }
            } ?: -1
            val deviation = row.indices.firstOrNull { idx ->
                idx != grade && idx != name && idx != guardian && row[idx].contains("偏差")
            } ?: -1
            val dateCols = row.indices.filter { idx ->
                idx != grade && idx != name && idx != guardian && idx != deviation &&
                    DATE_KEYS.any { row[idx].contains(it) }
            }
            val start = dateCols.firstOrNull { idx ->
                row[idx].contains("开始") || row[idx].contains("起")
            }
            val end = dateCols.firstOrNull { idx ->
                row[idx].contains("结束") || row[idx].contains("止")
            }
            var dateFrom = -1
            var dateTo = -1
            var date = -1
            if (start != null && end != null && start != end) {
                dateFrom = start
                dateTo = end
            } else {
                date = dateCols.firstOrNull() ?: -1
            }
            return i to Columns(
                grade = grade,
                name = name,
                guardian = guardian,
                dateFrom = dateFrom,
                dateTo = dateTo,
                date = date,
                deviation = deviation
            )
        }
        return null
    }

    private fun parseDateCell(text: String, today: LocalDate): Pair<LocalDate, LocalDate>? {
        if (text.isBlank()) return null
        for (separator in RANGE_SEPARATORS) {
            val index = text.indexOf(separator)
            if (index > 0) {
                val left = text.substring(0, index).trim()
                val right = text.substring(index + separator.length).trim()
                if (left.isNotEmpty() && right.isNotEmpty()) {
                    val from = parseDate(left, today) ?: return null
                    val to = parseDate(right, today) ?: return null
                    return from to to
                }
            }
        }
        val date = parseDate(text, today) ?: return null
        return date to date
    }

    private fun splitTime(raw: String): Pair<String, String> {
        val normalized = raw.trim().replace('：', ':').replace('　', ' ')
        if (normalized.isEmpty()) return "" to ""
        val kept = ArrayList<String>()
        var time = ""
        for (token in normalized.split(Regex("""\s+"""))) {
            if (token.isEmpty()) continue
            val parsed = normalizeTime(token)
            if (parsed != null) {
                if (time.isEmpty()) time = parsed
            } else {
                kept.add(token)
            }
        }
        return kept.joinToString(" ") to time
    }

    private fun normalizeTime(token: String): String? {
        val match = TIME_TOKEN.matchEntire(token) ?: return null
        val hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return String.format(Locale.ROOT, "%02d:%02d", hour, minute)
    }

    private fun removeTimeTokens(text: String): String {
        val kept = text.split(Regex("""\s+""")).filter { it.isNotEmpty() && normalizeTime(it) == null }
        return kept.joinToString(" ")
    }

    private fun monthDay(month: Int, day: Int, today: LocalDate): LocalDate? {
        val date = safeDate(today.year, month, day) ?: return null
        return if (date.isAfter(today.plusDays(30))) date.minusYears(1) else date
    }

    private fun safeDate(year: Int, month: Int, day: Int): LocalDate? =
        runCatching { LocalDate.of(year, month, day) }.getOrNull()

    private fun cell(row: List<String>, index: Int): String =
        if (index >= 0 && index < row.size) row[index].trim() else ""
}
