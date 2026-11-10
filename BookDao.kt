package com.sawtiyat.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY createdAt DESC")
    fun observeBooks(): Flow<List<Book>>

    @Insert
    suspend fun insert(book: Book): Long

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE books SET progressChars = :position WHERE id = :id")
    suspend fun updateProgress(id: Long, position: Int)

    @Query("SELECT * FROM books WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): Book?
}
