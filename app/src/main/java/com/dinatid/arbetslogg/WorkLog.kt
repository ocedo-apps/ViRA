package com.dinatid.arbetslogg

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "work_logs")
data class WorkLog(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val type: String, // Databasen lagrar fortfarande text som vanligt under huven
    val timestamp: Long,
    val ssid: String,
    val comment: String? = null
) {
    // --- FIX: Här låser vi namnen så att kompilatorn varnar dig direkt om du råkar stava fel ---
    companion object {
        const val TYPE_IN = "IN"
        const val TYPE_OUT = "UT"
        const val TYPE_OUT_AUTO = "UT (Auto)"
        const val TYPE_OUT_MANUAL = "UT (Manuell)"
    }
}