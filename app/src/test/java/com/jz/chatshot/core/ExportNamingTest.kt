package com.jz.chatshot.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ExportNamingTest {

    private lateinit var base: File
    private lateinit var root: File
    private lateinit var out: File

    @Before
    fun setUp() {
        base = Files.createTempDirectory("chatshot-export-test").toFile()
        root = File(base, "shots")
        out = File(base, "zips")
        root.mkdirs()
    }

    @After
    fun tearDown() {
        base.deleteRecursively()
    }

    @Test
    fun xlsxRosterExportFollowsRowOrderAndNamingRules() {
        val rows = listOf(
            listOf("年级", "姓名", "监护人", "沟通时间"),
            listOf("一年级", "张小明", "妈妈", "2026-10-01"),
            listOf("一年级", "张小明", "爸爸", "2026-10-01"),
            listOf("一年级", "李四", "奶奶", "2026-10-01"),
            listOf("一年级", "王/五", "妈妈", "2026-10-01"),
            listOf("一年级", "赵六", "妈妈", "2026-10-01"),
            listOf("一年级", "钱七", "爸爸", "2026-10-01"),
            listOf("3年级", "孙八", "妈妈", "2026-10-01"),
            listOf("三(2)班", "周九", "爸爸", "2026-10-01"),
            listOf("3年级", "吴十", "奶奶", "2026-10-01"),
            listOf("3(2)班", "郑一", "妈妈", "2026-10-01"),
            listOf("3年级", "王二", "爸爸", "2026-10-01"),
            listOf("", "陈三", "妈妈", "2026-10-01"),
            listOf("高一", "高小四", "爸爸", "2026-10-01")
        )
        val parsed = Roster.parse(
            Sheets.read(ByteArrayInputStream(xlsx(rows))),
            today = LocalDate.of(2026, 11, 17)
        )
        assertEquals(13, parsed.students.size)
        assertEquals((2..14).toList(), parsed.students.map { it.sourceRow })
        assertTrue(
            parsed.issues.any {
                it.row == 13 && it.message.contains("年级为空")
            }
        )

        val specs = mapOf(
            "张小明|妈妈" to listOf("png"),
            "张小明|爸爸" to listOf("png"),
            "王/五|妈妈" to listOf("png"),
            "赵六|妈妈" to listOf("png", "jpg"),
            "钱七|爸爸" to listOf("png"),
            "孙八|妈妈" to listOf("png"),
            "周九|爸爸" to listOf("png"),
            "吴十|奶奶" to listOf("png"),
            "郑一|妈妈" to listOf("png"),
            "陈三|妈妈" to listOf("png")
        )
        val withShots = parsed.students.map { student ->
            val extensions = specs["${student.name}|${student.guardian}"].orEmpty()
            val paths = extensions.mapIndexed { index, extension ->
                val relative = "raw/${student.id}_${index + 1}.$extension"
                File(root, relative).apply {
                    parentFile?.mkdirs()
                    writeText("shot-${student.id}-$index")
                }
                relative
            }
            student.copy(shots = paths)
        }

        // 故意打乱 Store 顺序，验证导出只按 xlsx 的 sourceRow 排序。
        val result = Archive.export(root, out, withShots.reversed())

        assertEquals(
            setOf("一年级.zip", "三年级.zip", "未分年级.zip"),
            result.zips.map { it.name }.toSet()
        )
        assertEquals(3, result.missingCount)

        val firstGrade = zipEntries(File(out, "一年级.zip"))
        assertEquals(
            listOf(
                "一年级/01_张小明.png",
                "一年级/02_张小明.png",
                "一年级/04_王五.png",
                "一年级/05_赵六_01.png",
                "一年级/05_赵六_02.jpg",
                "一年级/06_钱七.png"
            ),
            firstGrade
        )
        assertEquals(
            firstGrade.map { it.substringAfterLast('/') }.sorted(),
            firstGrade.map { it.substringAfterLast('/') }
        )
        assertTrue(firstGrade.none { it.contains("王/五") })

        val thirdGrade = zipEntries(File(out, "三年级.zip"))
        assertEquals(
            listOf(
                "三年级/01_孙八.png",
                "三年级/02_周九.png",
                "三年级/03_吴十.png",
                "三年级/04_郑一.png"
            ),
            thirdGrade
        )

        val ungraded = zipEntries(File(out, "未分年级.zip"))
        assertEquals(listOf("未分年级/01_陈三.png"), ungraded)

        val missing = result.missingList.readText(Charsets.UTF_8)
        assertTrue(missing.contains("共 3 人"))
        assertTrue(missing.contains("一年级  03  李四"))
        assertTrue(missing.contains("三年级  05  王二"))
        assertTrue(missing.contains("未分年级  02  高小四"))
    }

    private fun xlsx(rows: List<List<String>>): ByteArray {
        val sheet = buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?>""")
            append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
            rows.forEachIndexed { rowIndex, row ->
                append("<row r=\"${rowIndex + 1}\">")
                row.forEachIndexed { colIndex, value ->
                    val column = ('A'.code + colIndex).toChar()
                    append("<c r=\"$column${rowIndex + 1}\" t=\"inlineStr\"><is><t>")
                    append(escapeXml(value))
                    append("</t></is></c>")
                }
                append("</row>")
            }
            append("</sheetData></worksheet>")
        }
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            zip.write(sheet.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return output.toByteArray()
    }

    private fun escapeXml(value: String): String =
        value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

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
