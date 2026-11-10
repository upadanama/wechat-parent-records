package com.jz.chatshot.core

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

/**
 * 极简 xlsx 读取器：一次遍历 zip，取出共享字符串表和第一个工作表，
 * 再用 SAX 解析。只处理值，不关心样式。
 */
object XlsxReader {

    fun read(input: InputStream): List<List<String>> {
        var sharedBytes: ByteArray? = null
        var sheet1Bytes: ByteArray? = null
        var fallbackName: String? = null
        var fallbackBytes: ByteArray? = null

        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                when (entry.name) {
                    "xl/sharedStrings.xml" -> sharedBytes = zip.readBytes()
                    "xl/worksheets/sheet1.xml" -> sheet1Bytes = zip.readBytes()
                    else -> {
                        val name = entry.name
                        if (name.startsWith("xl/worksheets/") && name.endsWith(".xml")) {
                            val old = fallbackName
                            if (old == null || name < old) {
                                fallbackName = name
                                fallbackBytes = zip.readBytes()
                            }
                        }
                    }
                }
                zip.closeEntry()
            }
        }

        val shared = sharedBytes?.let { parseSharedStrings(it) } ?: emptyList()
        val sheet = sheet1Bytes ?: fallbackBytes ?: return emptyList()
        return parseSheet(sheet, shared)
    }

    private fun parseSharedStrings(bytes: ByteArray): List<String> {
        val result = ArrayList<String>()
        val handler = object : DefaultHandler() {
            private val text = StringBuilder()
            private var inSi = false
            private var inT = false
            private var rPhDepth = 0

            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
                when (tagName(qName, localName)) {
                    "si" -> {
                        inSi = true
                        text.setLength(0)
                    }
                    "rPh" -> rPhDepth++
                    "t" -> if (rPhDepth == 0) inT = true
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (inSi && inT && rPhDepth == 0) text.append(ch, start, length)
            }

            override fun endElement(uri: String?, localName: String?, qName: String?) {
                when (tagName(qName, localName)) {
                    "t" -> inT = false
                    "rPh" -> if (rPhDepth > 0) rPhDepth--
                    "si" -> {
                        result.add(text.toString())
                        inSi = false
                        text.setLength(0)
                    }
                }
            }
        }
        parseXml(bytes, handler)
        return result
    }

    private fun parseSheet(bytes: ByteArray, shared: List<String>): List<List<String>> {
        val rows = ArrayList<MutableList<String>>()
        val handler = object : DefaultHandler() {
            private var rowIndex = -1
            private var lastCol = -1
            private var cellCol = -1
            private var cellType: String? = null
            private val value = StringBuilder()
            private val inline = StringBuilder()
            private var inValue = false
            private var inInlineText = false

            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
                when (tagName(qName, localName)) {
                    "row" -> {
                        val r = attributes?.getValue("r")?.toIntOrNull()
                        rowIndex = if (r != null) r - 1 else rowIndex + 1
                        if (rowIndex < 0) rowIndex = 0
                        ensureRows(rowIndex)
                        lastCol = -1
                    }
                    "c" -> {
                        val ref = attributes?.getValue("r")
                        val col = if (ref != null) columnIndex(ref) else -1
                        cellCol = if (col >= 0) col else lastCol + 1
                        lastCol = cellCol
                        cellType = attributes?.getValue("t")
                        value.setLength(0)
                        inline.setLength(0)
                        inValue = false
                        inInlineText = false
                    }
                    "v" -> if (cellCol >= 0) inValue = true
                    "t" -> if (cellCol >= 0) inInlineText = true
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (inValue) {
                    value.append(ch, start, length)
                } else if (inInlineText) {
                    inline.append(ch, start, length)
                }
            }

            override fun endElement(uri: String?, localName: String?, qName: String?) {
                when (tagName(qName, localName)) {
                    "v" -> inValue = false
                    "t" -> inInlineText = false
                    "c" -> {
                        if (rowIndex >= 0 && cellCol >= 0) {
                            ensureRows(rowIndex)
                            val row = rows[rowIndex]
                            while (row.size <= cellCol) row.add("")
                            val type = cellType
                            val raw = when (type) {
                                "s" -> {
                                    val index = value.toString().trim().toIntOrNull()
                                    if (index != null && index in shared.indices) shared[index] else ""
                                }
                                "inlineStr" -> inline.toString()
                                else -> normalizeNumber(value.toString())
                            }
                            row[cellCol] = raw
                        }
                        cellCol = -1
                        cellType = null
                        inValue = false
                        inInlineText = false
                    }
                }
            }

            private fun ensureRows(index: Int) {
                while (rows.size <= index) rows.add(ArrayList())
            }
        }
        parseXml(bytes, handler)
        return rows.map { row -> row.map { it.trim() }.dropLastWhile { it.isEmpty() } }
    }

    private fun normalizeNumber(raw: String): String =
        raw.trim().replace(Regex("""^(-?\d+)\.0+$"""), "$1")

    private fun columnIndex(ref: String): Int {
        var n = 0
        var found = false
        for (ch in ref) {
            if (ch in 'A'..'Z' || ch in 'a'..'z') {
                n = n * 26 + (ch.uppercaseChar() - 'A' + 1)
                found = true
            } else {
                break
            }
        }
        return if (found) n - 1 else -1
    }

    private fun tagName(qName: String?, localName: String?): String {
        val name = qName ?: localName ?: return ""
        return name.substringAfter(':')
    }

    private fun parseXml(bytes: ByteArray, handler: DefaultHandler) {
        val factory = SAXParserFactory.newInstance()
        factory.isNamespaceAware = false
        // 防御 XXE；个别平台不支持该特性时忽略。
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        factory.newSAXParser().parse(ByteArrayInputStream(bytes), handler)
    }
}
