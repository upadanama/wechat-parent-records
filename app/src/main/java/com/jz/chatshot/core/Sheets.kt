package com.jz.chatshot.core

import java.io.BufferedInputStream
import java.io.InputStream

/** 根据文件头自动选择 .xls / .xlsx 读取器。 */
object Sheets {
    fun read(input: InputStream): List<List<String>> {
        val buffered = BufferedInputStream(input)
        buffered.mark(8)
        val head = ByteArray(4)
        var count = 0
        while (count < head.size) {
            val n = buffered.read(head, count, head.size - count)
            if (n <= 0) break
            count += n
        }
        buffered.reset()

        val b0 = if (count > 0) head[0].toInt() and 0xFF else -1
        val b1 = if (count > 1) head[1].toInt() and 0xFF else -1
        val b2 = if (count > 2) head[2].toInt() and 0xFF else -1
        val b3 = if (count > 3) head[3].toInt() and 0xFF else -1

        return when {
            b0 == 0xD0 && b1 == 0xCF && b2 == 0x11 && b3 == 0xE0 -> XlsReader.read(buffered)
            b0 == 0x50 && b1 == 0x4B -> XlsxReader.read(buffered)
            else -> throw IllegalArgumentException("不是 Excel 文件")
        }
    }
}
