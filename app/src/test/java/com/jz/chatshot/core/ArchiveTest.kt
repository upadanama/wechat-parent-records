package com.jz.chatshot.core

import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.time.LocalDate
import java.util.zip.ZipInputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ArchiveTest {

    private lateinit var base: File
    private lateinit var root: File
    private lateinit var out: File

    @Before
    fun setUp() {
        base = Files.createTempDirectory("chatshot-archive-test").toFile()
        root = File(base, "shots")
        out = File(base, "zips")
        root.mkdirs()
    }

    @After
    fun tearDown() {
        base.deleteRecursively()
    }

    @Test
    fun safeRemovesIllegalCharactersAndTrimsDots() {
        assertEquals("一年级", Archive.safe("一/年:级"))
        assertEquals("张三", Archive.safe(" 张*三. "))
        assertEquals("未命名", Archive.safe(" ... "))
        assertEquals("未命名", Archive.safe(""))
    }

    @Test
    fun shotPathAddsIncrementingSuffix() {
        val date = LocalDate.of(2026, 10, 12)
        val existing = ArrayList<String>()
        val first = Archive.shotPath("一年级", "张三", date, existing)
        assertEquals("一年级/张三/2026-10-12.png", first)
        existing.add(first)
        val second = Archive.shotPath("一年级", "张三", date, existing)
        assertEquals("一年级/张三/2026-10-12_2.png", second)
        existing.add(second)
        val third = Archive.shotPath("一年级", "张三", date, existing)
        assertEquals("一年级/张三/2026-10-12_3.png", third)
    }

    @Test
    fun shotPathHandlesUnknownDateAndUnsafeNames() {
        assertEquals(
            "一年级/张三/未知日期.png",
            Archive.shotPath("一/年:级", "张*三", null, emptyList())
        )
    }

    @Test
    fun pickShotDateUsesSingleRosterDate() {
        val date = LocalDate.of(2026, 10, 12)
        val visible = LocalDate.of(2026, 10, 20)
        assertEquals(date, Archive.pickShotDate(date, date, visible))
    }

    @Test
    fun pickShotDateUsesOnlyProvidedBound() {
        val date = LocalDate.of(2026, 10, 12)
        val visible = LocalDate.of(2026, 10, 20)
        assertEquals(date, Archive.pickShotDate(date, null, visible))
        assertEquals(date, Archive.pickShotDate(null, date, visible))
    }

    @Test
    fun pickShotDatePrefersVisibleDateForRange() {
        val from = LocalDate.of(2026, 10, 12)
        val to = LocalDate.of(2026, 10, 18)
        val visible = LocalDate.of(2026, 10, 15)
        assertEquals(visible, Archive.pickShotDate(from, to, visible))
    }

    @Test
    fun pickShotDateFallsBackWhenNoVisibleDate() {
        val from = LocalDate.of(2026, 10, 12)
        val to = LocalDate.of(2026, 10, 18)
        assertEquals(from, Archive.pickShotDate(from, to, null))
        assertNull(Archive.pickShotDate(null, null, null))
        val visible = LocalDate.of(2026, 10, 15)
        assertEquals(visible, Archive.pickShotDate(null, null, visible))
    }

    @Test
    fun folderGradeNormalizesToGradeFolder() {
        assertEquals("一年级", Archive.folderGrade("一年级"))
        assertEquals("三年级", Archive.folderGrade("3年级"))
        assertEquals("三年级", Archive.folderGrade("三(2)班"))
        assertEquals("三年级", Archive.folderGrade("3(2)班"))
        assertEquals("七年级", Archive.folderGrade("初一"))
        assertEquals("八年级", Archive.folderGrade("初二"))
        assertEquals("九年级", Archive.folderGrade("初三"))
        assertEquals("未分年级", Archive.folderGrade(""))
        assertEquals("未分年级", Archive.folderGrade("高一"))
    }

    @Test
    fun sequenceWidthIsAtLeastTwoAndGrows() {
        assertEquals(2, Archive.sequenceWidth(1))
        assertEquals(2, Archive.sequenceWidth(9))
        assertEquals(2, Archive.sequenceWidth(10))
        assertEquals(2, Archive.sequenceWidth(99))
        assertEquals(3, Archive.sequenceWidth(100))
    }

    @Test
    fun exportSingleGradeKeepsGradeFolder() {
        File(root, "raw/single.png").apply {
            parentFile.mkdirs()
            writeText("x")
        }
        out.mkdirs()
        File(out, "old.zip").writeText("old")
        File(out, "诊断_120000.txt").writeText("diag")
        val student = Student(
            id = 0,
            grade = "一年级",
            name = "张三",
            guardian = "妈妈",
            dateFrom = null,
            dateTo = null,
            shots = listOf("raw/single.png"),
            sourceRow = 2
        )
        val result = Archive.export(root, out, listOf(student))
        assertEquals(listOf("一年级.zip"), result.zips.map { it.name })
        assertEquals(0, result.missingCount)
        assertTrue(result.missingList.isFile)
        assertFalse(File(out, "old.zip").exists())
        assertTrue(File(out, "诊断_120000.txt").isFile)
        assertEquals(
            listOf("一年级/01_张三.png"),
            zipEntries(File(out, "一年级.zip"))
        )
    }

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
