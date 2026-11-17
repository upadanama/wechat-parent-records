package com.jz.chatshot.core

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object Archive {

    const val UNGRADED = "未分年级"
    const val MISSING_NAME = "缺失名单.txt"

    private val ILLEGAL = setOf('\\', '/', ':', '*', '?', '"', '<', '>', '|')
    private val ELEMENTARY_GRADE = Regex("""^[一二三四五六七八九]年级$""")
    private val GRADE_ORDER = listOf(
        "一年级", "二年级", "三年级", "四年级", "五年级",
        "六年级", "七年级", "八年级", "九年级"
    )

    data class ExportResult(
        val zips: List<File>,
        val missingList: File,
        val missingCount: Int
    )

    fun safe(s: String): String {
        val cleaned = s.filterNot { it in ILLEGAL }.trim { it.isWhitespace() || it == '.' }
        return cleaned.ifEmpty { "未命名" }
    }

    /** 内部截图路径仍按日期保存；导出时再按 xlsx 顺序重命名。 */
    fun shotPath(
        grade: String,
        name: String,
        date: LocalDate?,
        existing: Collection<String>
    ): String {
        val dir = "${safe(grade)}/${safe(name)}"
        val stem = date?.toString() ?: "未知日期"
        var path = "$dir/$stem.png"
        var index = 2
        while (path in existing) {
            path = "$dir/${stem}_$index.png"
            index++
        }
        return path
    }

    /**
     * 选择截图归档日期：
     * - 名单只有一个日期（起止相同或只有一端）时，直接使用名单日期；
     * - 名单是起止不同的范围时，优先用聊天页可见日期，取不到再用 dateFrom；
     * - 名单没有日期时，使用聊天页可见日期，可为 null。
     */
    fun pickShotDate(from: LocalDate?, to: LocalDate?, visible: LocalDate?): LocalDate? {
        if (from != null && to == null) return from
        if (from == null && to != null) return to
        if (from != null && to != null) {
            return if (from == to) from else (visible ?: from)
        }
        return visible
    }

    /**
     * 导出文件夹名：统一成「X年级」。
     * 初一/初二/初三映射到七/八/九年级；空值和无法识别的年级放入「未分年级」。
     */
    fun folderGrade(grade: String): String {
        val normalized = Roster.normGrade(grade)
        return when {
            ELEMENTARY_GRADE.matches(normalized) -> normalized
            normalized == "初一" -> "七年级"
            normalized == "初二" -> "八年级"
            normalized == "初三" -> "九年级"
            else -> UNGRADED
        }
    }

    /** 文件名序号补零位数：年级总人数所需位数，最少 2 位。 */
    fun sequenceWidth(count: Int): Int = maxOf(2, count.toString().length)

    /**
     * 按 xlsx 原始行顺序把学生截图导出为年级 ZIP。
     * 每个年级一个 ZIP，ZIP 内保留「X年级/」目录；每张图片命名为
     * 「序号_姓名.ext」或「序号_姓名_序号.ext」。
     */
    fun export(root: File, outDir: File, students: List<Student>): ExportResult {
        if (!outDir.exists()) outDir.mkdirs()
        // 只清理旧 ZIP 和缺失名单，保留诊断 txt 等其他文件。
        outDir.listFiles { file -> file.isFile && file.name.endsWith(".zip") }?.forEach { it.delete() }
        val missingFile = File(outDir, MISSING_NAME)
        missingFile.delete()

        val ordered = students.sortedWith(
            compareBy<Student> { it.sourceRow }.thenBy { it.id }
        )
        val groups = ordered.groupBy { folderGrade(it.grade) }
            .toList()
            .sortedWith(
                compareBy<Pair<String, List<Student>>> { gradeRank(it.first) }
                    .thenBy { it.first }
            )

        val zipFiles = ArrayList<File>()
        val missingLines = ArrayList<String>()
        for ((folder, group) in groups) {
            val width = sequenceWidth(group.size)
            val entries = LinkedHashMap<String, File>()
            group.forEachIndexed { index, student ->
                val sequence = (index + 1).toString().padStart(width, '0')
                val files = student.shots
                    .mapNotNull { resolve(root, it) }
                    .filter { it.isFile }
                if (files.isEmpty()) {
                    missingLines.add("$folder  $sequence  ${student.name}")
                    return@forEachIndexed
                }
                val safeName = safe(student.name)
                val shotWidth = sequenceWidth(files.size)
                files.forEachIndexed { shotIndex, file ->
                    val extension = file.extension.ifBlank { "png" }
                    val suffix = if (files.size > 1) {
                        "_" + (shotIndex + 1).toString().padStart(shotWidth, '0')
                    } else {
                        ""
                    }
                    val entryName = "$folder/${sequence}_${safeName}${suffix}.$extension"
                    entries[entryName] = file
                }
            }
            if (entries.isEmpty()) continue

            val zipFile = File(outDir, "$folder.zip")
            ZipOutputStream(
                BufferedOutputStream(FileOutputStream(zipFile)),
                Charsets.UTF_8
            ).use { zip ->
                for ((name, file) in entries) {
                    zip.putNextEntry(ZipEntry(name))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            zipFiles.add(zipFile)
        }

        val text = buildString {
            append('\uFEFF')
            if (missingLines.isEmpty()) {
                append("缺失名单：无\r\n")
            } else {
                append("缺失名单（无可用截图，共 ${missingLines.size} 人）\r\n")
                missingLines.forEach { append(it).append("\r\n") }
            }
        }
        missingFile.writeText(text, Charsets.UTF_8)

        return ExportResult(
            zips = zipFiles.sortedWith(
                compareBy<File> { gradeRank(it.nameWithoutExtension) }
                    .thenBy { it.name }
            ),
            missingList = missingFile,
            missingCount = missingLines.size
        )
    }

    /** 仅用于附件/展示排序；不改变 ZIP 文件名、ZIP 内部目录或导出序号。 */
    fun sortZipFiles(files: List<File>): List<File> =
        files.sortedWith(
            compareBy<File> { zipGradeRank(it.name) }.thenBy { it.name }
        )

    private fun zipGradeRank(fileName: String): Int =
        gradeRank(fileName.removeSuffix(".zip"))

    private fun gradeRank(folder: String): Int {
        val index = GRADE_ORDER.indexOf(folder)
        return if (index >= 0) index else GRADE_ORDER.size
    }

    private fun resolve(root: File, path: String): File {
        val file = File(path)
        return if (file.isAbsolute) file else File(root, path)
    }
}
