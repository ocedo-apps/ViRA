package com.dinatid.arbetslogg

import androidx.room.*

@Dao
interface WorkLogDao {
    @Query("SELECT * FROM work_logs ORDER BY timestamp ASC")
    suspend fun getAllLogs(): List<WorkLog>

    @Query("SELECT * FROM work_logs ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastLog(): WorkLog?

    // --- NY FUNKTION: Filtrerar direkt i databasen baserat på start- och sluttid ---
    @Query("SELECT * FROM work_logs WHERE timestamp BETWEEN :startTime AND :endTime ORDER BY timestamp ASC")
    suspend fun getLogsInTimeRange(startTime: Long, endTime: Long): List<WorkLog>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: WorkLog): Long

    @Query("DELETE FROM work_logs")
    suspend fun deleteAll(): Int

    @Update
    suspend fun update(log: WorkLog)

    @Delete
    suspend fun delete(log: WorkLog): Int
}