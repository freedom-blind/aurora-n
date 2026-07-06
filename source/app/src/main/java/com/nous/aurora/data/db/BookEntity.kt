package com.nous.aurora.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "books",
    indices = [Index(value = ["file_path"], unique = true)]
)
data class BookEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "file_path")
    val filePath: String,

    val title: String,
    val author: String = "",
    val publisher: String = "",
    val year: String = "",
    val language: String = "",
    val description: String = "",

    @ColumnInfo(name = "cover_path")
    val coverPath: String = "",

    val format: String,

    @ColumnInfo(name = "last_paragraph_index")
    val lastParagraphIndex: Int = 0,

    @ColumnInfo(name = "total_paragraphs")
    val totalParagraphs: Int = 0,

    @ColumnInfo(name = "added_at")
    val addedAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "last_read_at")
    val lastReadAt: Long = 0,

    @ColumnInfo(name = "file_modified_at")
    val fileModifiedAt: Long = 0,

    @ColumnInfo(name = "is_favorite")
    val isFavorite: Boolean = false,

    @ColumnInfo(name = "locator_json")
    val locatorJson: String = ""
)