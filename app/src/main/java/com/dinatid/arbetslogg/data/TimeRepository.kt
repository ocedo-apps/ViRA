package com.dinatid.arbetslogg.data

import android.content.Context
import android.content.SharedPreferences
import com.dinatid.arbetslogg.AppDatabase
import com.dinatid.arbetslogg.DailyNote
import com.dinatid.arbetslogg.WorkLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

class TimeRepository(private val context: Context) {

    private val database = AppDatabase.getDatabase(context)
    private val dailyNoteDao = database.dailyNoteDao() // --- NY: Hämtar vår nya DAO för kommentarer ---
    private val sharedPrefs: SharedPreferences = context.getSharedPreferences("arbetslogg_prefs", Context.MODE_PRIVATE)

    // --- DATABASHANTERING (Måste vara suspend och köras på bakgrundstrådar) ---

    suspend fun getAllLogs(): List<WorkLog> = withContext(Dispatchers.IO) {
        database.workLogDao().getAllLogs()
    }

    suspend fun getLastLog(): WorkLog? = withContext(Dispatchers.IO) {
        database.workLogDao().getLastLog()
    }

    suspend fun getLogsInTimeRange(start: Long, end: Long): List<WorkLog> = withContext(Dispatchers.IO) {
        database.workLogDao().getLogsInTimeRange(start, end)
    }

    suspend fun insertLog(log: WorkLog) = withContext(Dispatchers.IO) {
        database.workLogDao().insert(log)
    }

    suspend fun deleteLog(log: WorkLog) = withContext(Dispatchers.IO) {
        database.workLogDao().delete(log)
    }

    // --- NY FUNKTION: DAGBOK / DAGLIGA KOMMENTARER ---

    suspend fun getNoteForDay(dateStr: String): String? = withContext(Dispatchers.IO) {
        dailyNoteDao.getNoteForDay(dateStr)?.note
    }

    suspend fun saveNoteForDay(dateStr: String, noteText: String) = withContext(Dispatchers.IO) {
        dailyNoteDao.insertOrUpdateNote(DailyNote(dateStr, noteText))
    }

    // --- NY FUNKTION: Arkitektonisk flytt av midnattssplitten från WiFiService/ViewModel ---
    suspend fun insertAutoLogoutWithMidnightSplit(inTime: Long, outTime: Long, workplaceName: String) = withContext(Dispatchers.IO) {
        val inCal = Calendar.getInstance().apply { timeInMillis = inTime }
        val outCal = Calendar.getInstance().apply { timeInMillis = outTime }

        // Om passet startade och slutade på samma kalenderdag, gör en helt vanlig utcheckning
        if (inCal.get(Calendar.YEAR) == outCal.get(Calendar.YEAR) &&
            inCal.get(Calendar.DAY_OF_YEAR) == outCal.get(Calendar.DAY_OF_YEAR)) {

            insertLog(WorkLog(type = "UT (Auto)", timestamp = outTime, ssid = workplaceName))
        } else {
            // Om passet sträcker sig över midnatt påbörjas splittnings-loopen
            val currentStart = inCal.clone() as Calendar

            while (currentStart.get(Calendar.YEAR) < outCal.get(Calendar.YEAR) ||
                (currentStart.get(Calendar.YEAR) == outCal.get(Calendar.YEAR) &&
                        currentStart.get(Calendar.DAY_OF_YEAR) < outCal.get(Calendar.DAY_OF_YEAR))) {

                // Sätt sluttiden för dygnet till 23:59:59.999
                val endOfDay = currentStart.clone() as Calendar
                endOfDay.set(Calendar.HOUR_OF_DAY, 23)
                endOfDay.set(Calendar.MINUTE, 59)
                endOfDay.set(Calendar.SECOND, 59)
                endOfDay.set(Calendar.MILLISECOND, 999)

                insertLog(WorkLog(type = "UT (Auto)", timestamp = endOfDay.timeInMillis, ssid = workplaceName))

                // Hoppa fram till 00:00:00.000 nästa dag och starta ett nytt pass
                currentStart.add(Calendar.DAY_OF_YEAR, 1)
                currentStart.set(Calendar.HOUR_OF_DAY, 0)
                currentStart.set(Calendar.MINUTE, 0)
                currentStart.set(Calendar.SECOND, 0)
                currentStart.set(Calendar.MILLISECOND, 0)

                insertLog(WorkLog(type = "IN", timestamp = currentStart.timeInMillis, ssid = workplaceName))
            }
            // Logga ut slutgiltigt på den sista dagen där Wi-Fi-tappet faktiskt skedde
            insertLog(WorkLog(type = "UT (Auto)", timestamp = outTime, ssid = workplaceName))
        }
    }

    // --- GLOBALA HJÄLPFUNKTIONER (Städar bort kodduplicering från vyerna) ---

    fun roundTimestamp(timestamp: Long, minutesInterval: Int): Long {
        if (minutesInterval <= 0) return timestamp
        val msInMinute = 60000L
        val intervalMs = minutesInterval * msInMinute
        return ((timestamp + intervalMs / 2) / intervalMs) * intervalMs
    }

    // --- SHAREDPREFERENCES (Befintliga Wifi- & Systemflaggor) ---

    fun isWifiConnected(): Boolean {
        return sharedPrefs.getBoolean("is_wifi_connected", false)
    }

    fun setWifiConnected(connected: Boolean) {
        sharedPrefs.edit().putBoolean("is_wifi_connected", connected).apply()
    }

    fun isManualOverride(): Boolean {
        return sharedPrefs.getBoolean("manual_override", false)
    }

    fun setManualOverride(active: Boolean) {
        sharedPrefs.edit().putBoolean("manual_override", active).apply()
    }

    // --- DYNAMISKA INSTÄLLNINGAR ---

    fun getDailyGoalMinutes(): Int {
        val goal = sharedPrefs.getInt("work_goal_total_minutes", 480)
        return if (goal <= 0) 480 else goal
    }

    fun setDailyGoalMinutes(minutes: Int) {
        sharedPrefs.edit().putInt("work_goal_total_minutes", minutes).apply()
    }

    fun getLunchMinutes(): Int {
        val lunch = sharedPrefs.getInt("lunch_minutes", 45)
        return if (lunch < 0) 45 else lunch
    }

    fun setLunchMinutes(minutes: Int) {
        sharedPrefs.edit().putInt("lunch_minutes", minutes).apply()
    }

    fun getTargetSsid(): String {
        return sharedPrefs.getString("work_ssid", "")?.replace("\"", "")?.trim() ?: ""
    }

    fun setTargetSsid(ssid: String) {
        sharedPrefs.edit().putString("work_ssid", ssid).apply()
    }

    fun getRoundingInterval(): Int {
        return sharedPrefs.getInt("rounding_interval", 0)
    }

    fun setRoundingInterval(minutes: Int) {
        sharedPrefs.edit().putInt("rounding_interval", minutes).apply()
    }
}