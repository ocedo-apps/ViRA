package com.dinatid.arbetslogg

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface DailyNoteDao {
    @Query("SELECT * FROM daily_notes WHERE dateStr = :dateStr LIMIT 1")
    suspend fun getNoteForDay(dateStr: String): DailyNote?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateNote(dailyNote: DailyNote)
}