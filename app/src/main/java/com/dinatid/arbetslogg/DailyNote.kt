package com.dinatid.arbetslogg

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "daily_notes")
data class DailyNote(
    @PrimaryKey val dateStr: String, // Format: "YYYY-MM-DD" (t.ex. "2026-05-20")
    val note: String
)