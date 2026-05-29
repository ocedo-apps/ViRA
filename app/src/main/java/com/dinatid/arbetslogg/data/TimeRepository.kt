package com.dinatid.arbetslogg.data

import android.content.Context
import android.content.SharedPreferences
import com.dinatid.arbetslogg.AppDatabase
import com.dinatid.arbetslogg.DailyNote
import com.dinatid.arbetslogg.WorkLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import java.util.Calendar

sealed class AppEvent {
    object RefreshData : AppEvent()
    data class CountdownUpdate(val secondsLeft: Int) : AppEvent()
}

class TimeRepository private constructor(private val context: Context) {

    private val database = AppDatabase.getDatabase(context)
    private val dailyNoteDao = database.dailyNoteDao() 
    private val sharedPrefs: SharedPreferences = context.getSharedPreferences("arbetslogg_prefs", Context.MODE_PRIVATE)

    private val _events = MutableSharedFlow<AppEvent>(extraBufferCapacity = 64)
    val events = _events.asSharedFlow()

    suspend fun emitEvent(event: AppEvent) {
        _events.emit(event)
    }

    companion object {
        @Volatile
        private var INSTANCE: TimeRepository? = null

        fun getInstance(context: Context): TimeRepository {
            return INSTANCE ?: synchronized(this) {
                val instance = TimeRepository(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }

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
    suspend fun insertLogoutWithMidnightSplit(inTime: Long, outTime: Long, ssid: String, isManual: Boolean) = withContext(Dispatchers.IO) {
        val inCal = Calendar.getInstance().apply { timeInMillis = inTime }
        val outCal = Calendar.getInstance().apply { timeInMillis = outTime }
        val outType = if (isManual) WorkLog.TYPE_OUT_MANUAL else WorkLog.TYPE_OUT_AUTO

        // Om passet startade och slutade på samma kalenderdag, gör en helt vanlig utcheckning
        if (inCal.get(Calendar.YEAR) == outCal.get(Calendar.YEAR) &&
            inCal.get(Calendar.DAY_OF_YEAR) == outCal.get(Calendar.DAY_OF_YEAR)) {

            insertLog(WorkLog(type = outType, timestamp = outTime, ssid = ssid))
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

                insertLog(WorkLog(type = outType, timestamp = endOfDay.timeInMillis, ssid = ssid))

                // Hoppa fram till 00:00:00.000 nästa dag och starta ett nytt pass
                currentStart.add(Calendar.DAY_OF_YEAR, 1)
                currentStart.set(Calendar.HOUR_OF_DAY, 0)
                currentStart.set(Calendar.MINUTE, 0)
                currentStart.set(Calendar.SECOND, 0)
                currentStart.set(Calendar.MILLISECOND, 0)

                insertLog(WorkLog(type = WorkLog.TYPE_IN, timestamp = currentStart.timeInMillis, ssid = ssid))
            }
            // Logga ut slutgiltigt på den sista dagen där Wi-Fi-tappet faktiskt skedde
            insertLog(WorkLog(type = outType, timestamp = outTime, ssid = ssid))
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

    fun getCurrentSsid(): String? {
        return sharedPrefs.getString("current_ssid", null)
    }

    fun setCurrentSsid(ssid: String?) {
        sharedPrefs.edit().putString("current_ssid", ssid).apply()
    }

    fun isManualOverride(): Boolean {
        return sharedPrefs.getBoolean("manual_override", false)
    }

    fun setManualOverride(active: Boolean) {
        sharedPrefs.edit().putBoolean("manual_override", active).apply()
    }

    fun getManualOverrideSsid(): String? {
        return sharedPrefs.getString("manual_override_ssid", null)
    }

    fun setManualOverrideSsid(ssid: String?) {
        sharedPrefs.edit().putString("manual_override_ssid", ssid).apply()
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

    fun hasDeclinedWorkplaceSetup(): Boolean {
        return sharedPrefs.getBoolean("declined_workplace_setup", false)
    }

    fun setDeclinedWorkplaceSetup(declined: Boolean) {
        sharedPrefs.edit().putBoolean("declined_workplace_setup", declined).apply()
    }

    fun getAppTheme(): Int {
        return sharedPrefs.getInt("app_theme", 0) // 0 = Classic, 1 = Modern
    }

    fun setAppTheme(theme: Int) {
        sharedPrefs.edit().putInt("app_theme", theme).apply()
    }

    suspend fun getUserPattern(): UserPattern = withContext(Dispatchers.IO) {
        val logs = getAllLogs()
        PatternManager(logs).calculatePattern()
    }
}