package com.jz.chatshot.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RosterTest {

    private val today: LocalDate = LocalDate.of(2026, 11, 10)

    @Test
    fun parseDate_supportsCommonFormats() {
        val expected = LocalDate.of(2026, 10, 12)
        assertEquals(expected, Roster.parseDate("2026-10-12", today))
        assertEquals(expected, Roster.parseDate("2026/10/12", today))
        assertEquals(expected, Roster.parseDate("2026.10.12", today))
        assertEquals(expected, Roster.parseDate("2026年10月12日", today))
        assertEquals(expected, Roster.parseDate("20261012", today))
        assertEquals(expected, Roster.parseDate(" 2026年10月12日 08:30 ", today))
    }

    @Test
    fun parseDate_supportsMonthDayWithoutYear() {
        assertEquals(LocalDate.of(2026, 10, 12), Roster.parseDate("10月12日", today))
        assertEquals(LocalDate.of(2026, 10, 12), Roster.parseDate("10-12", today))
        assertEquals(LocalDate.of(2026, 10, 12), Roster.parseDate("10/12", today))
        assertEquals(LocalDate.of(2026, 10, 24), Roster.parseDate("10.24", today))
        assertEquals(LocalDate.of(2026, 10, 24), Roster.parseDate("10.24 19:31", today))
        assertEquals(LocalDate.of(2026, 1, 5), Roster.parseDate("1.5", today))
    }

    @Test
    fun parseDate_usesPreviousYearForFarFutureMonthDay() {
        val january = LocalDate.of(2026, 1, 5)
        assertEquals(LocalDate.of(2025, 12, 31), Roster.parseDate("12.31", january))
        assertEquals(LocalDate.of(2026, 1, 5), Roster.parseDate("1.5", january))
    }

    @Test
    fun parseDate_supportsExcelSerial() {
        assertEquals(LocalDate.of(1899, 12, 30).plusDays(45942), Roster.parseDate("45942", today))
        assertEquals(LocalDate.of(1899, 12, 30).plusDays(45942), Roster.parseDate("45942.0", today))
        assertEquals(LocalDate.of(2026, 10, 12), Roster.parseDate("20261012", today))
    }

    @Test
    fun parseDate_rejectsInvalidInput() {
        assertNull(Roster.parseDate("", today))
        assertNull(Roster.parseDate("abc", today))
        assertNull(Roster.parseDate("2026-02-30", today))
        assertNull(Roster.parseDate("99999999", today))
        assertNull(Roster.parseDate("10:30", today))
    }

    @Test
    fun normGrade_normalizesVariants() {
        assertEquals("一年级", Roster.normGrade("1年级"))
        assertEquals("三年级", Roster.normGrade("3"))
        assertEquals("一年级", Roster.normGrade("一（2）班"))
        assertEquals("一年级", Roster.normGrade("一年级2班"))
        assertEquals("一年级", Roster.normGrade("1年级2班"))
        assertEquals("三年级", Roster.normGrade("3班"))
        assertEquals("六年级", Roster.normGrade(" 六 年 级 "))
        assertEquals("初一", Roster.normGrade("初一"))
        assertEquals("高二", Roster.normGrade("高二"))
        assertEquals("七年级", Roster.normGrade("七年级"))
        assertEquals("", Roster.normGrade("  "))
    }

    @Test
    fun xlsxReader_readsSharedStringsMissingCellsAndNumbers() {
        val rows = XlsxReader.read(ByteArrayInputStream(minimalXlsx()))

        assertEquals(4, rows.size)
        assertEquals(listOf("姓名", "年级", "电话"), rows[0])
        assertEquals(listOf("张小明", "", "45942"), rows[1])
        assertTrue(rows[2].isEmpty())
        assertEquals(listOf("", "行内"), rows[3])
    }

    @Test
    fun sheets_readRecognizesXlsxBytes() {
        val rows = Sheets.read(ByteArrayInputStream(minimalXlsx()))
        assertEquals(listOf("姓名", "年级", "电话"), rows[0])
        assertEquals(listOf("张小明", "", "45942"), rows[1])
    }

    @Test
    fun sheets_rejectsNonExcelInput() {
        try {
            Sheets.read(ByteArrayInputStream("not excel".toByteArray()))
            fail("应当抛出 IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertEquals("不是 Excel 文件", e.message)
        }
    }

    @Test
    fun parse_handlesRealTableShapeWithTitleBlankRowsAndFullWidthColon() {
        val rows = listOf(
            listOf("2026年秋季学生沟通记录表"),
            emptyList(),
            listOf(
                "学生姓名", "年级", "与学生关系", "联系电话",
                "沟通时间", "效果或家长建议", "凭证（电话截屏或微聊截屏）"
            ),
            listOf("张小明", "六年级", "妈妈", "000", "10.24 19:31", "", ""),
            emptyList(),
            listOf("王小红", "六年级", "婆婆", "", "10.17 22：00", "", "")
        )

        val result = Roster.parse(rows, today = today)

        assertEquals(2, result.students.size)
        assertTrue(result.issues.isEmpty())
        val first = result.students[0]
        assertEquals("六年级", first.grade)
        assertEquals("张小明", first.name)
        assertEquals("妈妈", first.guardian)
        assertEquals("19:31", first.time)
        assertEquals(LocalDate.of(2026, 10, 24), first.dateFrom)
        assertEquals(LocalDate.of(2026, 10, 24), first.dateTo)
        val second = result.students[1]
        assertEquals("婆婆", second.guardian)
        assertEquals("22:00", second.time)
        assertEquals(LocalDate.of(2026, 10, 17), second.dateFrom)
    }

    @Test
    fun parse_reportsMissingHeader() {
        val result = Roster.parse(listOf(listOf("甲", "乙")))
        assertTrue(result.students.isEmpty())
        assertEquals(1, result.issues.size)
        assertEquals(RosterIssue(0, "未找到表头（需要包含 年级 和 姓名 列）"), result.issues[0])
    }

    @Test
    fun parse_reportsRowsMissingGradeOrName() {
        val rows = listOf(
            listOf("年级", "姓名", "监护人"),
            listOf("一年级", "", "妈妈"),
            listOf("", "张三", "妈妈"),
            listOf("一年级", "张三", "妈妈")
        )
        val result = Roster.parse(rows, today = today)
        assertEquals(1, result.students.size)
        assertEquals(2, result.issues.size)
        assertEquals("缺少年级/姓名", result.issues[0].message)
        assertEquals(2, result.issues[0].row)
        assertEquals("缺少年级/姓名", result.issues[1].message)
        assertEquals(3, result.issues[1].row)
    }

    @Test
    fun parse_reportsDuplicatesAndKeepsBothStudents() {
        val rows = listOf(
            listOf("年级", "姓名", "监护人"),
            listOf("一年级", "张三", "妈妈"),
            listOf("一年级", "张三", "妈妈"),
            listOf("一年级", "张三", "爸爸")
        )
        val result = Roster.parse(rows, today = today)
        assertEquals(3, result.students.size)
        assertEquals(1, result.issues.size)
        assertEquals(RosterIssue(3, "与第 2 行重复"), result.issues[0])
        assertEquals(listOf(0, 1, 2), result.students.map { it.id })
    }

    @Test
    fun parse_appliesDeviationToDateRange() {
        val rows = listOf(
            listOf("年级", "姓名", "监护人", "开始日期", "结束日期", "偏差"),
            listOf("一年级", "张三", "妈妈", "2026-10-12", "2026-10-18", "2")
        )
        val result = Roster.parse(rows, today = today)
        assertEquals(1, result.students.size)
        val student = result.students[0]
        assertEquals(LocalDate.of(2026, 10, 10), student.dateFrom)
        assertEquals(LocalDate.of(2026, 10, 20), student.dateTo)
    }

    @Test
    fun parse_supportsSingleCellRangeAndDefaultDates() {
        val rows = listOf(
            listOf("年级", "姓名", "监护人", "沟通时间"),
            listOf("一年级", "李四", "爸爸", "2026-10-12至2026-10-18"),
            listOf("二年级", "王五", "奶奶", "")
        )
        val result = Roster.parse(
            rows,
            defaultFrom = LocalDate.of(2026, 10, 1),
            defaultTo = LocalDate.of(2026, 10, 31),
            today = today
        )
        assertEquals(2, result.students.size)
        assertEquals(LocalDate.of(2026, 10, 12), result.students[0].dateFrom)
        assertEquals(LocalDate.of(2026, 10, 18), result.students[0].dateTo)
        assertEquals(LocalDate.of(2026, 10, 1), result.students[1].dateFrom)
        assertEquals(LocalDate.of(2026, 10, 31), result.students[1].dateTo)
    }

    @Test
    fun parse_invalidDateReportsIssueAndUsesDefaults() {
        val rows = listOf(
            listOf("年级", "姓名", "监护人", "沟通时间"),
            listOf("一年级", "张三", "妈妈", "某天")
        )
        val result = Roster.parse(
            rows,
            defaultFrom = LocalDate.of(2026, 10, 1),
            defaultTo = LocalDate.of(2026, 10, 31),
            today = today
        )
        assertEquals(1, result.issues.size)
        assertEquals(RosterIssue(2, "日期无法识别：某天"), result.issues[0])
        assertEquals(LocalDate.of(2026, 10, 1), result.students[0].dateFrom)
        assertEquals(LocalDate.of(2026, 10, 31), result.students[0].dateTo)
    }

    @Test
    fun parse_doesNotTreatDeviationHeaderContainingTimeAsDateColumn() {
        val rows = listOf(
            listOf("年级", "姓名", "监护人", "时间偏差", "沟通时间"),
            listOf("一年级", "张三", "妈妈", "1", "2026-10-12")
        )
        val result = Roster.parse(rows, today = today)
        assertTrue(result.issues.isEmpty())
        val student = result.students[0]
        assertEquals(LocalDate.of(2026, 10, 11), student.dateFrom)
        assertEquals(LocalDate.of(2026, 10, 13), student.dateTo)
    }

    private fun minimalXlsx(): ByteArray {
        val shared = """
            <?xml version="1.0" encoding="UTF-8"?>
            <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="4" uniqueCount="4">
              <si><t>姓名</t></si>
              <si><t>年级</t></si>
              <si><r><t>张</t></r><r><t>小明</t></r></si>
              <si><t>电话</t><rPh sb="0" eb="1"><t>dianhua</t></rPh></si>
            </sst>
        """.trimIndent()
        val sheet = """
            <?xml version="1.0" encoding="UTF-8"?>
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
              <sheetData>
                <row r="1">
                  <c r="A1" t="s"><v>0</v></c>
                  <c r="B1" t="s"><v>1</v></c>
                  <c r="C1" t="s"><v>3</v></c>
                </row>
                <row r="2">
                  <c r="A2" t="s"><v>2</v></c>
                  <c r="C2"><v>45942.0</v></c>
                </row>
                <row r="4">
                  <c r="B4" t="inlineStr"><is><t>行内</t></is></c>
                </row>
              </sheetData>
            </worksheet>
        """.trimIndent()

        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            // 工作表先出现，验证一次遍历不依赖条目顺序。
            zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            zip.write(sheet.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("xl/sharedStrings.xml"))
            zip.write(shared.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return output.toByteArray()
    }
}
