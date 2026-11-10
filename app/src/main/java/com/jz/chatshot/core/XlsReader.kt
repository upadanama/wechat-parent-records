package com.jz.chatshot.core

import java.io.InputStream
import jxl.Workbook

/** 用 jxl 读取老式 .xls，返回形状与 XlsxReader 一致。 */
object XlsReader {
    fun read(input: InputStream): List<List<String>> {
        val workbook = Workbook.getWorkbook(input)
        try {
            if (workbook.numberOfSheets == 0) return emptyList()
            val sheet = workbook.getSheet(0)
            val rows = ArrayList<List<String>>()
            for (r in 0 until sheet.rows) {
                val row = ArrayList<String>()
                for (c in 0 until sheet.columns) {
                    row.add(sheet.getCell(c, r).contents.trim())
                }
                while (row.isNotEmpty() && row.last().isEmpty()) row.removeAt(row.size - 1)
                rows.add(row)
            }
            return rows
        } finally {
            workbook.close()
        }
    }
}
