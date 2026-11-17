package com.jz.chatshot.core

import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import jxl.Workbook
import jxl.write.Label
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 用与真实 WPS .xls 相同的结构做回归：
 * 合并标题两行、第 3 行表头、年级间空分隔行、H 列「未」、Sheet2/3 为空。
 * 所有姓名均为虚构。
 */
class RealXlsExportRegressionTest {

    private lateinit var base: File
    private lateinit var root: File
    private lateinit var out: File

    @Before
    fun setUp() {
        base = Files.createTempDirectory("chatshot-real-xls-test").toFile()
        root = File(base, "shots")
        out = File(base, "zips")
        root.mkdirs()
    }

    @After
    fun tearDown() {
        base.deleteRecursively()
    }

    @Test
    fun realisticXlsStructureExportsFourGradesInSourceOrder() {
        val xls = File(base, "虚构名单.xls")
        writeRealisticXls(xls)

        val parsed = Roster.parse(
            Sheets.read(FileInputStream(xls)),
            today = LocalDate.of(2026, 10, 2)
        )
        assertEquals(51, parsed.students.size)
        assertTrue(parsed.issues.isEmpty())
        assertEquals(
            listOf("六年级", "四年级", "三年级", "五年级"),
            parsed.students.map { it.grade }.distinct()
        )
        assertEquals(
            mapOf("六年级" to 12, "四年级" to 17, "三年级" to 13, "五年级" to 9),
            parsed.students.groupingBy { it.grade }.eachCount()
        )
        assertEquals(
            setOf("四年级", "五年级"),
            parsed.students.filter { it.name == "张小明" }.map { it.grade }.toSet()
        )
        // 六年级第一行 H 列写了「未」，不应影响导入。
        assertTrue(parsed.students.any { it.grade == "六年级" && it.name == "测试学生01" })
        assertFalse(parsed.students.any { it.name.contains("未命名") })
        assertFalse(parsed.students.any { it.grade.contains("未分年级") })

        val withShots = parsed.students.map { student ->
            val relative = "raw/${student.id}.png"
            File(root, relative).apply {
                parentFile?.mkdirs()
                writeText("fake-shot-${student.id}")
            }
            student.copy(shots = listOf(relative))
        }

        // 故意反转，验证导出按 sourceRow 而不是传入列表顺序。
        val result = Archive.export(root, out, withShots.reversed())

        assertEquals(
            setOf("三年级.zip", "四年级.zip", "五年级.zip", "六年级.zip"),
            result.zips.map { it.name }.toSet()
        )
        assertEquals(0, result.missingCount)
        val missingText = result.missingList.readText(Charsets.UTF_8)
        assertTrue(missingText.contains("缺失名单：无"))
        assertFalse(missingText.contains("未分年级"))
        assertFalse(missingText.contains("未命名"))

        val byGrade = withShots.groupBy { it.grade }
        for ((grade, students) in byGrade) {
            val width = Archive.sequenceWidth(students.size)
            val expected = students.mapIndexed { index, student ->
                val sequence = (index + 1).toString().padStart(width, '0')
                "$grade/${sequence}_${Archive.safe(student.name)}.png"
            }
            assertEquals(expected, zipEntries(File(out, "$grade.zip")))
        }

        val four = zipEntries(File(out, "四年级.zip"))
        val five = zipEntries(File(out, "五年级.zip"))
        assertEquals(17, four.size)
        assertEquals(9, five.size)
        assertTrue(four.contains("四年级/09_张小明.png"))
        assertTrue(five.contains("五年级/02_张小明.png"))
        assertTrue(zipEntries(File(out, "六年级.zip")).contains("六年级/01_测试学生01.png"))
        assertTrue(zipEntries(File(out, "三年级.zip")).contains("三年级/01_测试学生29.png"))
    }

    private fun writeRealisticXls(file: File) {
        val workbook = Workbook.createWorkbook(file)
        val sheet = workbook.createSheet("Sheet1", 0)
        sheet.addCell(Label(0, 0, "2026年秋季学生沟通记录表"))
        sheet.addCell(Label(5, 0, "以下为虚构测试数据"))
        sheet.mergeCells(0, 0, 4, 1)
        sheet.mergeCells(5, 0, 6, 1)

        val headers = listOf(
            "学生姓名", "年级", "与学生关系", "联系电话",
            "沟通时间", "效果或家长建议", "凭证"
        )
        headers.forEachIndexed { column, text ->
            sheet.addCell(Label(column, 2, text))
        }

        val six = (1..12).map { testName(it) }
        val four = (13..20).map { testName(it) } +
            "张小明" +
            (21..28).map { testName(it) }
        val three = (29..41).map { testName(it) }
        val five = listOf(testName(42), "张小明") + (43..49).map { testName(it) }
        val grades = listOf(
            "六年级" to six,
            "四年级" to four,
            "三年级" to three,
            "五年级" to five
        )

        var row = 3
        grades.forEachIndexed { gradeIndex, (grade, names) ->
            names.forEachIndexed { index, name ->
                sheet.addCell(Label(0, row, name))
                sheet.addCell(Label(1, row, grade))
                sheet.addCell(Label(2, row, if (index % 2 == 0) "妈妈" else "爸爸"))
                sheet.addCell(Label(3, row, "000"))
                sheet.addCell(Label(4, row, "2026-10-01"))
                if (name == "测试学生01" || name == "测试学生29") {
                    sheet.addCell(Label(7, row, "未"))
                }
                row++
            }
            if (gradeIndex < grades.lastIndex) {
                // 年级之间的空分隔行：姓名和年级均为空。
                row++
            }
        }

        workbook.createSheet("Sheet2", 1)
        workbook.createSheet("Sheet3", 2)
        workbook.write()
        workbook.close()
    }

    private fun testName(index: Int): String =
        "测试学生${index.toString().padStart(2, '0')}"

    private fun zipEntries(file: File): List<String> {
        val names = ArrayList<String>()
        ZipInputStream(FileInputStream(file), Charsets.UTF_8).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) names.add(entry.name)
                zip.closeEntry()
            }
        }
        return names
    }
}
