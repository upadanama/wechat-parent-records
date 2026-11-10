package com.jz.chatshot.core

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object Archive {

    private val ILLEGAL = setOf('\\', '/', ':', '*', '?', '"', '<', '>', '|')

    fun safe(s: String): String {
        val cleaned = s.filterNot { it in ILLEGAL }.trim { it.isWhitespace() || it == '.' }
        return cleaned.ifEmpty { "未命名" }
    }

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

    fun zipGrades(root: File, outDir: File): List<File> {
        if (!outDir.exists()) outDir.mkdirs()
        // 只清理旧的 .zip，保留同目录下的诊断 txt。
        outDir.listFiles { file -> file.isFile && file.name.endsWith(".zip") }?.forEach { it.delete() }
        if (!root.isDirectory) return emptyList()

        val result = ArrayList<File>()
        val gradeDirs = root.listFiles { file -> file.isDirectory }?.sortedBy { it.name } ?: emptyList()
        for (dir in gradeDirs) {
            val files = dir.walkTopDown()
                .filter { it.isFile }
                .sortedBy { it.relativeTo(dir).invariantSeparatorsPath }
                .toList()
            if (files.isEmpty()) continue

            val zipFile = File(outDir, "${dir.name}.zip")
            ZipOutputStream(
                BufferedOutputStream(FileOutputStream(zipFile)),
                Charsets.UTF_8
            ).use { zip ->
                for (file in files) {
                    val relative = file.relativeTo(dir).invariantSeparatorsPath
                    zip.putNextEntry(ZipEntry("${dir.name}/$relative"))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            result.add(zipFile)
        }
        return result.sortedBy { it.name }
    }
}
