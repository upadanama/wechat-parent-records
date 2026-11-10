package com.jz.chatshot.core

import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatTimeTest {

    private val now: LocalDateTime = LocalDateTime.of(2026, 11, 10, 12, 0) // 星期二

    @Test
    fun parseTodayTime() {
        assertEquals(
            LocalDateTime.of(2026, 11, 10, 19, 31),
            ChatTime.parse("19:31", now)
        )
    }

    @Test
    fun parseFullWidthColon() {
        assertEquals(
            LocalDateTime.of(2026, 11, 10, 19, 31),
            ChatTime.parse("19：31", now)
        )
        assertEquals(
            LocalDateTime.of(2026, 10, 24, 19, 31),
            ChatTime.parse("10月24日 19：31", now)
        )
    }

    @Test
    fun parseYesterday() {
        assertEquals(
            LocalDateTime.of(2026, 11, 9, 19, 31),
            ChatTime.parse("昨天 19:31", now)
        )
        assertEquals(
            LocalDateTime.of(2026, 11, 9, 19, 31),
            ChatTime.parse("昨天19:31", now)
        )
    }

    @Test
    fun parseWeekdayUsesMostRecentPastDay() {
        assertEquals(
            LocalDateTime.of(2026, 11, 3, 19, 31),
            ChatTime.parse("星期二 19:31", now)
        )
        assertEquals(
            LocalDateTime.of(2026, 11, 3, 19, 31),
            ChatTime.parse("周二 19:31", now)
        )
        assertEquals(
            LocalDateTime.of(2026, 11, 4, 19, 31),
            ChatTime.parse("周三 19:31", now)
        )
        assertEquals(
            LocalDateTime.of(2026, 11, 8, 19, 31),
            ChatTime.parse("星期日 19:31", now)
        )
    }

    @Test
    fun parseMonthDayUsesCurrentYear() {
        assertEquals(
            LocalDateTime.of(2026, 10, 24, 19, 31),
            ChatTime.parse("10月24日 19:31", now)
        )
    }

    @Test
    fun parseMonthDayUsesPreviousYearWhenFarInFuture() {
        val january = LocalDateTime.of(2026, 1, 5, 12, 0)
        assertEquals(
            LocalDateTime.of(2025, 12, 31, 19, 31),
            ChatTime.parse("12月31日 19:31", january)
        )
    }

    @Test
    fun parseFullDateWithYear() {
        assertEquals(
            LocalDateTime.of(2026, 10, 24, 19, 31),
            ChatTime.parse("2026年10月24日 19:31", now)
        )
        assertEquals(
            LocalDateTime.of(2025, 10, 24, 19, 31),
            ChatTime.parse("2025年10月24日 19:31", now)
        )
    }

    @Test
    fun parseEveningAndAfternoonPeriods() {
        assertEquals(
            LocalDateTime.of(2026, 10, 24, 19, 31),
            ChatTime.parse("10月24日 晚上7:31", now)
        )
        assertEquals(
            LocalDateTime.of(2026, 10, 24, 19, 31),
            ChatTime.parse("10月24日晚上 7:31", now)
        )
        assertEquals(
            LocalDateTime.of(2026, 10, 24, 16, 5),
            ChatTime.parse("10月24日 下午4:05", now)
        )
    }

    @Test
    fun parseNoonAndMidnightPeriods() {
        assertEquals(
            LocalDateTime.of(2026, 10, 24, 22, 30),
            ChatTime.parse("10月24日 中午10:30", now)
        )
        assertEquals(
            LocalDateTime.of(2026, 10, 24, 11, 30),
            ChatTime.parse("10月24日 中午11:30", now)
        )
        assertEquals(
            LocalDateTime.of(2026, 10, 24, 0, 0),
            ChatTime.parse("10月24日 凌晨12:00", now)
        )
    }

    @Test
    fun parseDateAndTimeWithoutSpace() {
        assertEquals(
            LocalDateTime.of(2026, 10, 24, 19, 31),
            ChatTime.parse("10月24日19:31", now)
        )
        assertEquals(
            LocalDateTime.of(2026, 10, 24, 19, 31),
            ChatTime.parse("2026年10月24日晚上7:31", now)
        )
    }

    @Test
    fun parseOnlyDateReturnsNull() {
        assertNull(ChatTime.parse("10月24日", now))
        assertNull(ChatTime.parse("2026年10月24日", now))
        assertNull(ChatTime.parse("昨天", now))
        assertNull(ChatTime.parse("星期二", now))
    }

    @Test
    fun parseProseReturnsNull() {
        assertNull(ChatTime.parse("我们10月24日见", now))
        assertNull(ChatTime.parse("10月24日见 19:31", now))
        assertNull(ChatTime.parse("10月24日 19:31 我们", now))
        assertNull(ChatTime.parse("时间：10月24日 19:31", now))
    }

    @Test
    fun parseInvalidDateOrTimeReturnsNull() {
        assertNull(ChatTime.parse("10月24日 25:00", now))
        assertNull(ChatTime.parse("10月24日 19:60", now))
        assertNull(ChatTime.parse("13月1日 19:31", now))
        assertNull(ChatTime.parse("2026年2月30日 19:31", now))
    }

    private val date: LocalDate = LocalDate.of(2026, 10, 24)
    private val seps: List<LocalDateTime> = listOf(
        LocalDateTime.of(2026, 10, 24, 18, 0),
        LocalDateTime.of(2026, 10, 24, 19, 5),
        LocalDateTime.of(2026, 10, 24, 19, 45),
        LocalDateTime.of(2026, 10, 24, 21, 30),
        LocalDateTime.of(2026, 10, 25, 9, 0)
    )

    @Test
    fun pickAnchorEmptyTimeReturnsEarliest() {
        assertEquals(
            LocalDateTime.of(2026, 10, 24, 18, 0),
            ChatTime.pickAnchor(seps, date, "", true)
        )
    }

    @Test
    fun pickAnchorMatchesHourFirst() {
        assertEquals(
            LocalDateTime.of(2026, 10, 24, 19, 5),
            ChatTime.pickAnchor(seps, date, "19:31", true)
        )
    }

    @Test
    fun pickAnchorIgnoresMinutesAndTimeExact() {
        assertEquals(
            LocalDateTime.of(2026, 10, 24, 19, 5),
            ChatTime.pickAnchor(seps, date, "19:08", true)
        )
        assertEquals(
            LocalDateTime.of(2026, 10, 24, 19, 5),
            ChatTime.pickAnchor(seps, date, "19", false)
        )
        assertEquals(
            LocalDateTime.of(2026, 10, 24, 21, 30),
            ChatTime.pickAnchor(seps, date, "21:08", true)
        )
        assertEquals(
            LocalDateTime.of(2026, 10, 24, 21, 30),
            ChatTime.pickAnchor(seps, date, "21", false)
        )
    }

    @Test
    fun pickAnchorFallsBackToLatestBeforeHour() {
        assertEquals(
            LocalDateTime.of(2026, 10, 24, 19, 45),
            ChatTime.pickAnchor(seps, date, "20:00", true)
        )
        assertEquals(
            LocalDateTime.of(2026, 10, 24, 21, 30),
            ChatTime.pickAnchor(seps, date, "22:00", true)
        )
    }

    @Test
    fun pickAnchorAllLaterReturnsEarliest() {
        assertEquals(
            LocalDateTime.of(2026, 10, 24, 18, 0),
            ChatTime.pickAnchor(seps, date, "08:00", true)
        )
    }

    @Test
    fun pickAnchorFiltersDateAndHandlesEmptyList() {
        assertEquals(
            LocalDateTime.of(2026, 10, 25, 9, 0),
            ChatTime.pickAnchor(seps, LocalDate.of(2026, 10, 25), "", true)
        )
        assertNull(ChatTime.pickAnchor(seps, LocalDate.of(2026, 10, 26), "", true))
        assertNull(ChatTime.pickAnchor(emptyList(), date, "19:00", true))
    }

    @Test
    fun pickAnchorSortsUnsortedInput() {
        val unsorted = listOf(
            LocalDateTime.of(2026, 10, 24, 21, 30),
            LocalDateTime.of(2026, 10, 24, 18, 0),
            LocalDateTime.of(2026, 10, 24, 19, 45),
            LocalDateTime.of(2026, 10, 24, 19, 5)
        )
        assertEquals(
            LocalDateTime.of(2026, 10, 24, 18, 0),
            ChatTime.pickAnchor(unsorted, date, "", true)
        )
        assertEquals(
            LocalDateTime.of(2026, 10, 24, 19, 5),
            ChatTime.pickAnchor(unsorted, date, "19:30", true)
        )
    }
}
