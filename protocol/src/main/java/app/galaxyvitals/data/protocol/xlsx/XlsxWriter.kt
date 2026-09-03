package app.galaxyvitals.data.protocol.xlsx

import java.io.BufferedWriter
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes the smallest spreadsheet Excel will open: a zip of OOXML parts.
 *
 * A 30 s ECG is 15,000 sample rows, so rows are streamed straight into the zip
 * entry rather than assembled in memory, and text is written as inline strings
 * so there is no shared-string table to accumulate.
 *
 * Numbers are formatted under [Locale.ROOT]. A spreadsheet cell value is an XSD
 * double, so a comma decimal separator picked up from the device locale would
 * silently corrupt every measurement in the file.
 */
object XlsxWriter {

    /** Cell style indices declared by [STYLES_XML]. */
    private const val STYLE_DEFAULT = 0
    private const val STYLE_BOLD = 1

    fun write(out: OutputStream, build: Workbook.() -> Unit) {
        val zip = ZipOutputStream(out)
        val workbook = Workbook(zip)
        try {
            workbook.build()
            workbook.writePackageParts()
        } finally {
            zip.finish()
        }
    }

    class Workbook internal constructor(private val zip: ZipOutputStream) {
        private val sheetNames = ArrayList<String>()

        /**
         * Appends a worksheet. Sheets are written in call order and named in the
         * workbook part afterwards, so the caller never has to declare them up
         * front.
         */
        fun sheet(name: String, body: Sheet.() -> Unit) {
            val index = sheetNames.size + 1
            sheetNames.add(sanitizeSheetName(name, index))
            zip.putNextEntry(ZipEntry("xl/worksheets/sheet$index.xml"))
            val writer = BufferedWriter(OutputStreamWriter(zip, Charsets.UTF_8), 1 shl 16)
            writer.write(XML_DECL)
            writer.write(
                "<worksheet xmlns=\"$NS_MAIN\">" +
                    "<sheetFormatPr defaultRowHeight=\"15\"/>" +
                    "<sheetData>",
            )
            Sheet(writer).body()
            writer.write("</sheetData></worksheet>")
            writer.flush()
            zip.closeEntry()
        }

        internal fun writePackageParts() {
            require(sheetNames.isNotEmpty()) { "a workbook needs at least one sheet" }
            entry("[Content_Types].xml", contentTypesXml(sheetNames.size))
            entry("_rels/.rels", ROOT_RELS_XML)
            entry("xl/workbook.xml", workbookXml(sheetNames))
            entry("xl/_rels/workbook.xml.rels", workbookRelsXml(sheetNames.size))
            entry("xl/styles.xml", STYLES_XML)
        }

        private fun entry(path: String, body: String) {
            zip.putNextEntry(ZipEntry(path))
            zip.write(body.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }

    class Sheet internal constructor(private val writer: BufferedWriter) {
        private var rowIndex = 0

        fun row(body: Row.() -> Unit) {
            rowIndex += 1
            writer.write("<row r=\"$rowIndex\">")
            Row(writer, rowIndex).body()
            writer.write("</row>")
        }

        /** A row of plain text cells. */
        fun textRow(vararg values: String?) = row { values.forEach { text(it) } }

        /** A row of bold text cells, for a header line. */
        fun headerRow(vararg values: String?) = row { values.forEach { text(it, bold = true) } }
    }

    class Row internal constructor(
        private val writer: BufferedWriter,
        private val rowIndex: Int,
    ) {
        private var column = 0

        fun text(value: String?, bold: Boolean = false) {
            val ref = nextRef()
            if (value.isNullOrEmpty()) {
                writeEmpty(ref, bold)
                return
            }
            val style = if (bold) " s=\"$STYLE_BOLD\"" else ""
            writer.write("<c r=\"$ref\"$style t=\"inlineStr\"><is><t xml:space=\"preserve\">")
            writer.write(escapeText(value))
            writer.write("</t></is></c>")
        }

        /** A decimal number, rounded to [decimals]. Non-finite values write an empty cell. */
        fun number(value: Double?, decimals: Int = 3, bold: Boolean = false) {
            val ref = nextRef()
            if (value == null || !value.isFinite()) {
                writeEmpty(ref, bold)
                return
            }
            writeNumeric(ref, String.format(Locale.ROOT, "%.${decimals}f", value), bold)
        }

        fun integer(value: Long?, bold: Boolean = false) {
            val ref = nextRef()
            if (value == null) {
                writeEmpty(ref, bold)
                return
            }
            writeNumeric(ref, value.toString(), bold)
        }

        fun integer(value: Int?, bold: Boolean = false) = integer(value?.toLong(), bold)

        fun boolean(value: Boolean?) {
            val ref = nextRef()
            if (value == null) {
                writeEmpty(ref, false)
                return
            }
            writer.write("<c r=\"$ref\" t=\"b\"><v>${if (value) 1 else 0}</v></c>")
        }

        /** Advances past a column without emitting a cell. */
        fun skip() {
            column += 1
        }

        private fun writeNumeric(ref: String, literal: String, bold: Boolean) {
            val style = if (bold) " s=\"$STYLE_BOLD\"" else ""
            writer.write("<c r=\"$ref\"$style><v>$literal</v></c>")
        }

        private fun writeEmpty(ref: String, bold: Boolean) {
            val style = if (bold) " s=\"$STYLE_BOLD\"" else " s=\"$STYLE_DEFAULT\""
            writer.write("<c r=\"$ref\"$style/>")
        }

        private fun nextRef(): String {
            column += 1
            return columnName(column) + rowIndex
        }
    }

    /** 1 -> A, 26 -> Z, 27 -> AA. */
    fun columnName(oneBased: Int): String {
        require(oneBased >= 1) { "column is one-based" }
        val sb = StringBuilder()
        var remaining = oneBased
        while (remaining > 0) {
            val rem = (remaining - 1) % 26
            sb.append(('A' + rem))
            remaining = (remaining - 1) / 26
        }
        return sb.reverse().toString()
    }

    /**
     * Escapes for XML text content and drops the control characters XML 1.0
     * forbids outright, which a device string can carry but no reader accepts.
     */
    fun escapeText(value: String): String {
        val sb = StringBuilder(value.length + 16)
        for (ch in value) {
            when {
                ch == '&' -> sb.append("&amp;")
                ch == '<' -> sb.append("&lt;")
                ch == '>' -> sb.append("&gt;")
                ch == '\t' || ch == '\n' || ch == '\r' -> sb.append(ch)
                ch.code < 0x20 -> Unit
                ch.code == 0x7F -> Unit
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    /** Excel rejects these characters in a sheet name, and caps it at 31 chars. */
    private fun sanitizeSheetName(name: String, index: Int): String {
        val cleaned = name.filterNot { it in "[]:*?/\\" }.trim().take(31)
        return cleaned.ifEmpty { "Sheet$index" }
    }

    private const val XML_DECL = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
    private const val NS_MAIN =
        "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val NS_REL =
        "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    private fun contentTypesXml(sheetCount: Int): String = buildString {
        append(XML_DECL)
        append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
        append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
        append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
        append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>")
        append("<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>")
        for (index in 1..sheetCount) {
            append("<Override PartName=\"/xl/worksheets/sheet$index.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>")
        }
        append("</Types>")
    }

    private const val ROOT_RELS_XML =
        "$XML_DECL<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"$NS_REL/officeDocument\" Target=\"xl/workbook.xml\"/>" +
            "</Relationships>"

    private fun workbookXml(names: List<String>): String = buildString {
        append(XML_DECL)
        append("<workbook xmlns=\"$NS_MAIN\" xmlns:r=\"$NS_REL\"><sheets>")
        names.forEachIndexed { index, name ->
            append("<sheet name=\"${escapeText(name)}\" sheetId=\"${index + 1}\" r:id=\"rId${index + 1}\"/>")
        }
        append("</sheets></workbook>")
    }

    private fun workbookRelsXml(sheetCount: Int): String = buildString {
        append(XML_DECL)
        append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
        for (index in 1..sheetCount) {
            append("<Relationship Id=\"rId$index\" Type=\"$NS_REL/worksheet\" Target=\"worksheets/sheet$index.xml\"/>")
        }
        append("<Relationship Id=\"rId${sheetCount + 1}\" Type=\"$NS_REL/styles\" Target=\"styles.xml\"/>")
        append("</Relationships>")
    }

    /** Two cell formats: index 0 plain, index 1 bold. */
    private const val STYLES_XML =
        "$XML_DECL<styleSheet xmlns=\"$NS_MAIN\">" +
            "<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font>" +
            "<font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>" +
            "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill>" +
            "<fill><patternFill patternType=\"gray125\"/></fill></fills>" +
            "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>" +
            "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
            "<cellXfs count=\"2\">" +
            "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>" +
            "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>" +
            "</cellXfs></styleSheet>"
}
