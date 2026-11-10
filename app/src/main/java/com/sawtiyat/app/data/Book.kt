package com.sawtiyat.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "books")
data class Book(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val author: String = "",
    val content: String,
    val progressChars: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)
