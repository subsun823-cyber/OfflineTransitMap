package com.example.offlinetransitmap

import java.io.PushbackReader
import java.io.Reader

// GTFS CSV: quoted commas/newlines, escaped quotes, CRLF and optional UTF-8 BOM.
internal class GtfsCsv(reader: Reader) {
    private val input = PushbackReader(reader.buffered(), 1)
    fun row(): List<String>? {
        val result = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var closed = false
        var any = false
        while (true) {
            val value = input.read()
            if (value == -1) {
                require(!quoted) { "CSVの引用符が閉じていません" }
                return if (!any) null else result.apply { add(field.toString()) }
            }
            any = true
            val ch = value.toChar()
            if (quoted) {
                if (ch == '"') {
                    val next = input.read()
                    if (next == '"'.code) field.append('"') else {
                        quoted = false; closed = true
                        if (next != -1) input.unread(next)
                    }
                } else field.append(ch)
            } else when (ch) {
                ',' -> { result.add(field.toString()); field.setLength(0); closed = false }
                '\n', '\r' -> {
                    if (ch == '\r') { val next = input.read(); if (next != -1 && next != '\n'.code) input.unread(next) }
                    result.add(field.toString()); return result
                }
                '"' -> { require(field.isEmpty() && !closed) { "CSVの引用符が不正です" }; quoted = true }
                else -> { require(!closed) { "CSVの引用符の後に不正な文字があります" }; field.append(ch) }
            }
            require(field.length <= 100_000 && result.size <= 100) { "CSVの行が大きすぎます" }
        }
    }

    fun records(consume: (Map<String, String>) -> Unit) {
        val header = requireNotNull(row()) { "CSVが空です" }.map { it.removePrefix("\uFEFF") }
        require(header.toSet().size == header.size) { "CSVの列が重複しています" }
        while (true) {
            val values = row() ?: break
            if (values == listOf("")) continue
            require(values.size == header.size) { "CSVの列数が一致しません" }
            consume(header.zip(values).toMap())
        }
    }
}
