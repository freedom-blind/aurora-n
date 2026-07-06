package com.nous.aurora.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [BookEntity::class, BookmarkEntity::class, AnnotationEntity::class],
    version = 4,
    exportSchema = false
)
abstract class AuroraDatabase : RoomDatabase() {

    abstract fun bookDao(): BookDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun annotationDao(): AnnotationDao

    companion object {
        @Volatile
        private var INSTANCE: AuroraDatabase? = null

        fun getInstance(context: Context): AuroraDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AuroraDatabase::class.java,
                    "aurora.db"
                )
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }

        /**
         * Migration from version 2 (SQLiteOpenHelper with raw SQL, aurora-v style)
         * to version 3 (Room with locator_json column).
         * Schema: add locator_json columns to books, bookmarks, annotations tables.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add locator_json column to books table
                db.execSQL("ALTER TABLE books ADD COLUMN locator_json TEXT NOT NULL DEFAULT ''")
                // Add locator_json column to bookmarks table
                db.execSQL("ALTER TABLE bookmarks ADD COLUMN locator_json TEXT NOT NULL DEFAULT ''")
                // Add locator_json column to annotations table
                db.execSQL("ALTER TABLE annotations ADD COLUMN locator_json TEXT NOT NULL DEFAULT ''")
                // Add modified_time column to annotations table (if not exists)
                db.execSQL("ALTER TABLE annotations ADD COLUMN modified_time INTEGER NOT NULL DEFAULT 0")
                // Create indices
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_bookmarks_locator ON bookmarks(book_id, locator_json)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_annotations_locator ON annotations(book_id, locator_json)")
            }
        }

        /**
         * Migration from version 3 to version 4.
         * Schema is already compatible; ensure indices exist.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_books_sort ON books(last_read_at DESC, title)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_books_fav ON books(is_favorite)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_bookmarks_paragraph ON bookmarks(book_id, paragraph_index)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_bookmarks_locator ON bookmarks(book_id, locator_json)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_annotations_paragraph ON annotations(book_id, paragraph_index)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_annotations_locator ON annotations(book_id, locator_json)")
            }
        }
    }
}
