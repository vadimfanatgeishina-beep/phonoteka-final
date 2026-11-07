package com.phonoteka.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [TrackEntity::class, CandidateEntity::class, CatalogPostEntity::class, CatalogCommentEntity::class],
    version = 3,
    exportSchema = true
)
abstract class PhonotekaDatabase : RoomDatabase() {

    abstract fun trackDao(): TrackDao
    abstract fun catalogPostDao(): CatalogPostDao

    companion object {
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS catalog_posts (id INTEGER NOT NULL PRIMARY KEY, userId INTEGER NOT NULL, title TEXT NOT NULL, body TEXT NOT NULL, fetchedAt INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS catalog_comments (postId INTEGER NOT NULL, id INTEGER NOT NULL, name TEXT NOT NULL, email TEXT NOT NULL, body TEXT NOT NULL, PRIMARY KEY(postId, id))")
            }
        }
        @Volatile
        private var INSTANCE: PhonotekaDatabase? = null

        fun get(context: Context): PhonotekaDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?:                 Room.databaseBuilder(
                    context.applicationContext,
                    PhonotekaDatabase::class.java,
                    "phonoteka.db"
                )
                    .addMigrations(MIGRATION_2_3)
                    .build()
                    .also { INSTANCE = it }
            }
        }

        /** Для тестов — in-memory база. */
        fun getInMemory(context: Context): PhonotekaDatabase {
            return Room.inMemoryDatabaseBuilder(
                context.applicationContext,
                PhonotekaDatabase::class.java
            )
                .allowMainThreadQueries()
                .build()
        }
    }
}
