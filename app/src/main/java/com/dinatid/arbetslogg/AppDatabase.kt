package com.dinatid.arbetslogg

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// --- VIKTIGT: VERSIONEN ÄR NU UPPHÖJD TILL 4 OCH HAR TVÅ ENTITIES ---
@Database(entities = [WorkLog::class, DailyNote::class], version = 4)
abstract class AppDatabase : RoomDatabase() {
    abstract fun workLogDao(): WorkLogDao
    abstract fun dailyNoteDao(): DailyNoteDao // <- NY DAO REGISTERAD

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE work_logs ADD COLUMN comment TEXT DEFAULT NULL")
            }
        }

        // --- NY MAGI: MIGRERING FRÅN VERSION 2 TILL 3 ---
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Vi skapar den nya tabellen "daily_notes" via ren SQL
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `daily_notes` (
                        `dateStr` TEXT NOT NULL, 
                        `note` TEXT NOT NULL, 
                        PRIMARY KEY(`dateStr`)
                    )
                """.trimIndent())
            }
        }

        // --- NY MAGI: MIGRERING FRÅN VERSION 3 TILL 4 ---
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE work_logs ADD COLUMN isManuallyEdited INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "work_log_database"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4) // <- BERÄTTAR FÖR ROOM OM ALLA MIGRERINGAR
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}