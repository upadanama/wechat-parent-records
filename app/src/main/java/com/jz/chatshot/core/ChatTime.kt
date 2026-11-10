package com.jz.chatshot.core

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** 微信聊天页时间分隔的纯解析逻辑。 */
object ChatTime {

    private val FULL_DATE = Regex(
        """^(\d{4})年(\d{1,2})月(\d{1,2})日\s*(?:(凌晨|早上|上午|中午|下午|晚上)\s*)?(\d{1,2}):(\d{2})"""
    )
    private val MONTH_DAY = Regex(
        """^(\d{1,2})月(\d{1,2})日\s*(?:(凌晨|早上|上午|中午|下午|晚上)\s*)?(\d{1,2}):(\d{2})"""
    )
    private val YESTERDAY = Regex(
        """^昨天\s*(?:(凌晨|早上|上午|中午|下午|晚上)\s*)?(\d{1,2}):(\d{2})"""
    )
    private val WEEKDAY = Regex(
        """^(?:星期|周)([一二三四五六日天])\s*(?:(凌晨|早上|上午|中午|下午|晚上)\s*)?(\d{1,2}):(\d{2})"""
    )
    private val TODAY_TIME = Regex(
        """^(?:(凌晨|早上|上午|中午|下午|晚上)\s*)?(\d{1,2}):(\d{2})"""
    )

    /** 解析微信聊天页里的时间分隔文字；整段文字必须完全是时间格式才算，否则返回 null。 */
    fun parse(text: String, now: LocalDateTime = LocalDateTime.now()): LocalDateTime? {
        val normalized = text.trim().replace('：', ':').replace('　', ' ')
        if (normalized.isEmpty()) return null

        FULL_DATE.matchEntire(normalized)?.let { match ->
            val date = safeDate(
                match.groupValues[1].toInt(),
                match.groupValues[2].toInt(),
                match.groupValues[3].toInt()
            ) ?: return null
            return combine(date, match.groupValues[4], match.groupValues[5], match.groupValues[6])
        }
        MONTH_DAY.matchEntire(normalized)?.let { match ->
            val date = monthDay(
                now.toLocalDate(),
                match.groupValues[1].toInt(),
                match.groupValues[2].toInt()
            ) ?: return null
            return combine(date, match.groupValues[3], match.groupValues[4], match.groupValues[5])
        }
        YESTERDAY.matchEntire(normalized)?.let { match ->
            val date = now.toLocalDate().minusDays(1)
            return combine(date, match.groupValues[1], match.groupValues[2], match.groupValues[3])
        }
        WEEKDAY.matchEntire(normalized)?.let { match ->
            val target = weekdayValue(match.groupValues[1].first())
            var diff = now.dayOfWeek.value - target
            if (diff <= 0) diff += 7
            val date = now.toLocalDate().minusDays(diff.toLong())
            return combine(date, match.groupValues[2], match.groupValues[3], match.groupValues[4])
        }
        TODAY_TIME.matchEntire(normalized)?.let { match ->
            return combine(
                now.toLocalDate(),
                match.groupValues[1],
                match.groupValues[2],
                match.groupValues[3]
            )
        }
        return null
    }

    /** 在某一天的全部时间分隔里选出应该定位到的那一个。 */
    @Suppress("UNUSED_PARAMETER")
    fun pickAnchor(
        seps: List<LocalDateTime>,
        date: LocalDate,
        time: String,
        timeExact: Boolean
    ): LocalDateTime? {
        val day = seps.filter { it.toLocalDate() == date }.sorted()
        if (day.isEmpty()) return null
        if (time.isBlank()) return day.first()

        val hour = time.substringBefore(':').trim().toIntOrNull() ?: return day.first()
        if (hour !in 0..23) return day.first()

        val inHour = day.filter { it.hour == hour }
        if (inHour.isNotEmpty()) return inHour.first()

        val before = day.filter { it.hour <= hour }
        if (before.isNotEmpty()) return before.last()
        return day.first()
    }

    private fun combine(
        date: LocalDate,
        period: String,
        hourText: String,
        minuteText: String
    ): LocalDateTime? {
        var hour = hourText.toIntOrNull() ?: return null
        val minute = minuteText.toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        hour = when {
            (period == "下午" || period == "晚上") && hour < 12 -> hour + 12
            period == "中午" && hour < 11 -> hour + 12
            period == "凌晨" && hour == 12 -> 0
            else -> hour
        }
        if (hour !in 0..23) return null
        return LocalDateTime.of(date, LocalTime.of(hour, minute))
    }

    private fun monthDay(today: LocalDate, month: Int, day: Int): LocalDate? {
        val date = safeDate(today.year, month, day) ?: return null
        return if (date.isAfter(today.plusDays(30))) date.minusYears(1) else date
    }

    private fun safeDate(year: Int, month: Int, day: Int): LocalDate? =
        runCatching { LocalDate.of(year, month, day) }.getOrNull()

    private fun weekdayValue(char: Char): Int = when (char) {
        '一' -> 1
        '二' -> 2
        '三' -> 3
        '四' -> 4
        '五' -> 5
        '六' -> 6
        '日', '天' -> 7
        else -> 7
    }
}
