package com.dinatid.arbetslogg

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// --- VIKTIGT: VERSIONEN ÄR NU UPPHÖJD TILL 2 ---
@Database(entities = [WorkLog::class], version = 2)
abstract class AppDatabase : RoomDatabase() {
    abstract fun workLogDao(): WorkLogDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        // --- HÄR ÄR MAGIN: MIGRERINGEN FRÅN VERSION 1 TILL 2 ---
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Vi säger till databasen att lägga till en ny kolumn som heter "comment"
                db.execSQL("ALTER TABLE work_logs ADD COLUMN comment TEXT DEFAULT NULL")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "work_log_database"
                )
                    .addMigrations(MIGRATION_1_2) // <- BERÄTTAR FÖR ROOM ATT ANVÄNDA VÅR MIGRERING
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}