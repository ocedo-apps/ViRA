package com.dinatid.arbetslogg.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dinatid.arbetslogg.WorkLog
import com.dinatid.arbetslogg.R
import com.dinatid.arbetslogg.data.AppEvent
import com.dinatid.arbetslogg.data.TimeRepository
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class HistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = TimeRepository.getInstance(application)

    private val _calendarDays = MutableStateFlow<List<CalendarDay>>(emptyList())
    val calendarDays: StateFlow<List<CalendarDay>> = _calendarDays.asStateFlow()

    private val _currentMonthText = MutableStateFlow("")
    val currentMonthText: StateFlow<String> = _currentMonthText.asStateFlow()

    private var currentCalendar = Calendar.getInstance()

    init {
        loadMonth()
        listenToEvents()
    }

    private fun listenToEvents() {
        viewModelScope.launch {
            repository.events.collect { event ->
                if (event is AppEvent.RefreshData) {
                    loadMonth()
                }
            }
        }
    }

    fun refresh() {
        loadMonth()
    }

    fun changeMonth(offset: Int) {
        currentCalendar.add(Calendar.MONTH, offset)
        loadMonth()
    }

    suspend fun getNoteForDay(date: Date): String? {
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(date)
        return repository.getNoteForDay(dateStr)
    }

    fun saveNoteForDay(date: Date, note: String) {
        viewModelScope.launch {
            val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(date)
            repository.saveNoteForDay(dateStr, note)
        }
    }

    private fun loadMonth() {
        viewModelScope.launch {
            val logs = repository.getAllLogs()
            val lastLogOverall = logs.lastOrNull()
            val isCurrentlyInOverall = lastLogOverall?.type == WorkLog.TYPE_IN

            val monthName = currentCalendar.getDisplayName(Calendar.MONTH, Calendar.LONG, Locale("sv", "SE"))?.uppercase() ?: ""
            val year = currentCalendar.get(Calendar.YEAR)
            _currentMonthText.value = "$monthName $year"

            val daysList = mutableListOf<CalendarDay>()

            val cal = currentCalendar.clone() as Calendar
            cal.set(Calendar.DAY_OF_MONTH, 1)
            val firstDayOfMonthMs = cal.timeInMillis
            val lastDayOfMonthMs = firstDayOfMonthMs + (cal.getActualMaximum(Calendar.DAY_OF_MONTH).toLong() * 86400000L)
            
            // Hämta externa kalenderhändelser för månaden
            val externalEventsMap = getCalendarEventsForPeriod(firstDayOfMonthMs, lastDayOfMonthMs)

            val firstDayOfWeek = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7

            for (i in 0 until firstDayOfWeek) {
                daysList.add(CalendarDay(null, "", 0, false, null, false, false, false, emptyList(), null, emptyList()))
            }

            val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
            val todayCal = Calendar.getInstance()

            for (day in 1..daysInMonth) {
                cal.set(Calendar.DAY_OF_MONTH, day)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)

                val startOfDayMs = cal.timeInMillis
                val endOfDayMs = startOfDayMs + 86399999L

                val isToday = (cal.get(Calendar.YEAR) == todayCal.get(Calendar.YEAR) &&
                        cal.get(Calendar.DAY_OF_YEAR) == todayCal.get(Calendar.DAY_OF_YEAR))

                val holidayName = HolidayManager.getHolidayName(cal)
                val isHoliday = holidayName != null

                val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
                val isWeekend = dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY

                val dayLogs = logs.filter { it.timestamp in startOfDayMs..endOfDayMs }.sortedBy { it.timestamp }

                // --- AVROUNDNINGSFIX: Vi räknar ut och avrundar minuter pass för pass istället för att slänga sekunder ---
                var dayMin = 0
                var isIn = false
                var lastInTs = 0L

                for (l in dayLogs) {
                    if (l.type == WorkLog.TYPE_IN && !isIn) {
                        lastInTs = l.timestamp
                        isIn = true
                    } else if (l.type.startsWith(WorkLog.TYPE_OUT) && isIn) {
                        dayMin += Math.round((l.timestamp - lastInTs).toDouble() / 60000.0).toInt()
                        isIn = false
                    }
                }

                if (isIn && isToday) {
                    dayMin += Math.round((System.currentTimeMillis() - lastInTs).toDouble() / 60000.0).toInt()
                }

                // --- LUNCH-DETEKTOR ---
                var lunchTxt: String? = null
                var lastOutTs = 0L
                val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

                for (l in dayLogs) {
                    if (l.type.startsWith(WorkLog.TYPE_OUT)) {
                        lastOutTs = l.timestamp
                    } else if (l.type == WorkLog.TYPE_IN && lastOutTs != 0L) {
                        val gapMin = (l.timestamp - lastOutTs) / 60000
                        val calTmp = Calendar.getInstance().apply { timeInMillis = lastOutTs }
                        val hourOfDay = calTmp.get(Calendar.HOUR_OF_DAY)

                        // Heuristik: Om gapet är 25-90 minuter och startar mellan 10-14
                        if (gapMin in 25..90 && hourOfDay in 10..13) {
                            val lunchLabel = getApplication<Application>().getString(R.string.calendar_lunch)
                            lunchTxt = "$lunchLabel\n${timeFormat.format(Date(lastOutTs))}\n${timeFormat.format(Date(l.timestamp))}"
                            break // Vi tar bara första lunchen för att inte kladda ner rutan
                        }
                    }
                }

                val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(cal.time)
                val dayExternalEvents = externalEventsMap[dateStr] ?: emptyList()

                daysList.add(
                    CalendarDay(
                        date = cal.time,
                        dayNumber = day.toString(),
                        loggedMinutes = dayMin,
                        isHoliday = isHoliday,
                        holidayName = holidayName,
                        isToday = isToday,
                        isWeekend = isWeekend,
                        isOngoing = isIn && isToday && isCurrentlyInOverall, // Sätts till true om ett pass pågår idag och systemet är incheckat
                        dayLogs = dayLogs,
                        lunchText = lunchTxt,
                        externalEvents = dayExternalEvents
                    )
                )
            }

            _calendarDays.value = daysList
        }
    }

    private fun getCalendarEventsForPeriod(startTimeMs: Long, endTimeMs: Long): Map<String, List<String>> {
        val eventsMap = HashMap<String, MutableList<String>>()
        val context = getApplication<Application>().applicationContext

        val prefs = context.getSharedPreferences("arbetslogg_prefs", Context.MODE_PRIVATE)
        val isCalendarEnabled = prefs.getBoolean("use_calendar_integration", false)
        if (!isCalendarEnabled) return eventsMap

        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CALENDAR)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return eventsMap
        }

        try {
            val uri = android.provider.CalendarContract.Instances.CONTENT_URI
            val builder = uri.buildUpon()
            android.content.ContentUris.appendId(builder, startTimeMs)
            android.content.ContentUris.appendId(builder, endTimeMs)

            val projection = arrayOf(
                android.provider.CalendarContract.Instances.TITLE,
                android.provider.CalendarContract.Instances.BEGIN
            )

            val cursor = context.contentResolver.query(builder.build(), projection, null, null, null)

            cursor?.use {
                val titleIdx = it.getColumnIndex(android.provider.CalendarContract.Instances.TITLE)
                val beginIdx = it.getColumnIndex(android.provider.CalendarContract.Instances.BEGIN)
                val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

                while (it.moveToNext()) {
                    val title = (it.getString(titleIdx) ?: "").lowercase(Locale.getDefault()).trim()
                    val beginMs = it.getLong(beginIdx)
                    val dateStr = dateFormat.format(Date(beginMs))

                    // --- FILTER: Spara bara relevanta status-händelser ---
                    val isRelevant = title.contains("sjuk") || 
                                    title.contains("vab") || 
                                    title.contains("semester") || 
                                    title.contains("ledig")

                    if (isRelevant) {
                        if (!eventsMap.containsKey(dateStr)) {
                            eventsMap[dateStr] = ArrayList()
                        }
                        eventsMap[dateStr]?.add(title)
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("CALENDAR_READ_VM", "Kunde inte läsa kalender: ${e.message}")
        }

        return eventsMap
    }
}