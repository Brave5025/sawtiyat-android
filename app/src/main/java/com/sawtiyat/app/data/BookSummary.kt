package com.sawtiyat.app.data

/** Lightweight Room projection used by the library; it intentionally excludes the full book body. */
data class BookSummary(
    val id: Long,
    val title: String,
    val author: String,
    val contentLength: Int,
    val progressChars: Int,
    val createdAt: Long
)
