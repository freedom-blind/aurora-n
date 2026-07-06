package com.nous.aurora.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "annotations",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["book_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["book_id", "paragraph_index"]),
        Index(value = ["book_id", "locator_json"])
    ]
)
data class AnnotationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "book_id")
    val bookId: Long,

    @ColumnInfo(name = "paragraph_index")
    val paragraphIndex: Int = 0,

    val text: String,

    @ColumnInfo(name = "created_time")
    val createdTime: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "modified_time")
    val modifiedTime: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "locator_json")
    val locatorJson: String = ""
)
