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

    fun zipGrades(root: File, outDir: File): List<File> {
        if (!outDir.exists()) outDir.mkdirs()
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
