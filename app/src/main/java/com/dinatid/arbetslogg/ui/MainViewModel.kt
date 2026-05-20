package com.dinatid.arbetslogg.ui

import android.app.Application
import android.content.Context
import android.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dinatid.arbetslogg.R
import com.dinatid.arbetslogg.WorkLog
import com.dinatid.arbetslogg.data.TimeRepository
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = TimeRepository(application)
    private val context = application.applicationContext

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    fun refreshData(selectedDateOffset: Int, wifiCountdownSeconds: Int) {
        viewModelScope.launch {
            val logs = repository.getAllLogs()
            val dailyGoalTotalMin = repository.getDailyGoalMinutes()
            val lunchMin = repository.getLunchMinutes()
            val isWifiConnected = repository.isWifiConnected()

            val prefs = context.getSharedPreferences("arbetslogg_prefs", Context.MODE_PRIVATE)
            val smartHelpMode = prefs.getInt("smart_help_mode", 1)
            val consultantGoalHours = prefs.getInt("consultant_monthly_goal", 160)
            val consultantGoalMinutes = consultantGoalHours * 60

            val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, selectedDateOffset) }
            val startOfDay = (cal.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }

            var dayMs = 0L
            val dayLogs = logs.filter { it.timestamp in startOfDay.timeInMillis..(startOfDay.timeInMillis + 86400000) }.sortedBy { it.timestamp }
            val lastLogOverall = logs.sortedBy { it.timestamp }.lastOrNull()

            val isCurrentlyIn = lastLogOverall?.type == WorkLog.TYPE_IN
            val lastInLog = logs.filter { it.type == WorkLog.TYPE_IN }.maxByOrNull { it.timestamp }

            if (dayLogs.isNotEmpty()) {
                var lastInTs = 0L; var isIn = false
                for (l in dayLogs) {
                    if (l.type == WorkLog.TYPE_IN && !isIn) { lastInTs = l.timestamp; isIn = true }
                    else if (l.type.startsWith(WorkLog.TYPE_OUT) && isIn) { dayMs += (l.timestamp - lastInTs); isIn = false }
                }
                if (isIn && selectedDateOffset == 0) dayMs += (System.currentTimeMillis() - lastInTs)
            } else if (isCurrentlyIn && selectedDateOffset == 0) {
                dayMs += (System.currentTimeMillis() - startOfDay.timeInMillis)
            }

            val dayMin = (dayMs / 60000).toInt()

            val currentDateText = if (selectedDateOffset == 0) {
                context.getString(R.string.date_today)
            } else if (selectedDateOffset == -1) {
                context.getString(R.string.date_yesterday)
            } else {
                SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(cal.time).uppercase()
            }

            val nextDayAlpha = if (selectedDateOffset == 0) 0.3f else 1.0f
            val isNextDayClickable = selectedDateOffset != 0

            var centerTimeText: String
            var centerTimeColor: Int
            var isCountdownVisible = false
            var currentSsidText: String
            var currentWorkplaceText = ""
            var inTimeCircleText: String
            var statusTextStr = ""
            var statusTextColor = Color.GRAY

            // Hämta eventuell helgdag för den valda dagen
            val holidayNameOnSelectedDay = HolidayManager.getHolidayName(cal)

            if (wifiCountdownSeconds >= 0 && selectedDateOffset == 0) {
                centerTimeText = String.format("%02d:%02d", wifiCountdownSeconds / 60, wifiCountdownSeconds % 60)
                centerTimeColor = Color.parseColor("#F44336")
                isCountdownVisible = true
                currentSsidText = "LOGGAR UT OM:"
                currentWorkplaceText = ""
                inTimeCircleText = context.getString(R.string.empty_value)
                statusTextStr = context.getString(R.string.warning_outside_zone)
                statusTextColor = Color.parseColor("#F44336")
            } else {
                centerTimeText = String.format("%02d:%02d", dayMin / 60, dayMin % 60)
                centerTimeColor = Color.parseColor("#4CAF50")

                if (isCurrentlyIn && lastInLog != null) {
                    val rawSsid = lastInLog.ssid ?: ""
                    val isManual = rawSsid.startsWith("Manuell", ignoreCase = true)

                    currentSsidText = if (isManual) "MANUELL IN" else "AUTO IN"
                    currentWorkplaceText = when {
                        rawSsid.contains("-") -> rawSsid.substringAfter("-").trim()
                        isManual -> ""
                        else -> rawSsid
                    }

                    val timeFormatted = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(lastInLog.timestamp))
                    inTimeCircleText = context.getString(R.string.circle_in_time, timeFormatted)
                } else {
                    if (selectedDateOffset == 0 && lastLogOverall != null) {
                        val outType = lastLogOverall.type
                        val rawSsid = lastLogOverall.ssid ?: ""

                        val isManualOut = outType.contains("Manuell", ignoreCase = true)
                        currentSsidText = if (isManualOut) "MANUELL UT" else "AUTO UT"

                        currentWorkplaceText = when {
                            rawSsid.contains("-") -> rawSsid.substringAfter("-").trim()
                            rawSsid.equals("Manuell", ignoreCase = true) -> ""
                            else -> rawSsid
                        }
                    } else if (selectedDateOffset == 0) {
                        currentSsidText = context.getString(R.string.status_checked_out)
                        currentWorkplaceText = ""
                    } else {
                        currentSsidText = context.getString(R.string.empty_value)
                        currentWorkplaceText = ""
                    }
                    inTimeCircleText = context.getString(R.string.empty_value)
                }

                if (selectedDateOffset == 0) {
                    val progressPercent = if (dailyGoalTotalMin > 0) (dayMin.toFloat() / dailyGoalTotalMin.toFloat() * 100f).toInt() else 0

                    statusTextStr = when {
                        // --- HÄR BYTER VI UT GAMIFIERINGSTEXTEN VID HELGDAG ---
                        holidayNameOnSelectedDay != null && !isCurrentlyIn && dayMin == 0 -> {
                            val resId = HolidayManager.getGreetingResId(holidayNameOnSelectedDay)
                            if (resId != 0) {
                                context.getString(resId)
                            } else {
                                // Om en specifik hälsning saknas, formatera dynamiskt (t.ex. "GLAD ALLA HELGONS DAG!")
                                context.getString(R.string.holiday_default_format, holidayNameOnSelectedDay.uppercase())
                            }
                        }

                        // Dina vanliga gamifieringstexter rullar på som vanligt annars:
                        !isCurrentlyIn && dayMin == 0 -> context.getString(R.string.pepp_new_day)
                        isCurrentlyIn && progressPercent < 20 -> context.getString(R.string.pepp_opportunities)
                        isCurrentlyIn && progressPercent in 20..79 -> context.getString(R.string.pepp_lunch)
                        isCurrentlyIn && progressPercent in 80..99 -> context.getString(R.string.pepp_almost_there)
                        isCurrentlyIn && progressPercent in 100..119 -> context.getString(R.string.pepp_goal_reached)
                        isCurrentlyIn && progressPercent in 120..139 -> context.getString(R.string.pepp_overtime)
                        isCurrentlyIn && progressPercent in 140..159 -> context.getString(R.string.pepp_late_worker)
                        isCurrentlyIn && progressPercent >= 160 -> context.getString(R.string.pepp_forgot_phone)
                        !isCurrentlyIn && progressPercent >= 100 -> context.getString(R.string.pepp_good_work)
                        else -> if (isCurrentlyIn) context.getString(R.string.pepp_checked_in) else context.getString(R.string.pepp_checked_out)
                    }
                    statusTextColor = if (isCurrentlyIn) Color.parseColor("#4CAF50") else Color.GRAY
                }
            }

            val workProgressBarProgress = if (dailyGoalTotalMin > 0) (dayMin.toFloat() / dailyGoalTotalMin.toFloat() * 100f).toInt() else 0
            val isSecondsVisible = selectedDateOffset == 0 && wifiCountdownSeconds < 0

            val dailyGoalAfterLunch = dailyGoalTotalMin - lunchMin
            val now = Calendar.getInstance()
            val startOfMonth = (now.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, 1) }

            var workDaysUntilToday = 0
            val currentDayOfMonth = now.get(Calendar.DAY_OF_MONTH)
            val checkCal = startOfMonth.clone() as Calendar

            var totalWorkDaysInMonth = 0
            val endOfMonth = (startOfMonth.clone() as Calendar).apply {
                set(Calendar.DAY_OF_MONTH, getActualMaximum(Calendar.DAY_OF_MONTH))
            }
            val checkCalTotal = startOfMonth.clone() as Calendar

            // --- NYTT: Räknar bort röda dagar från totala månadsmålet ---
            while (checkCalTotal.before(endOfMonth) || checkCalTotal.timeInMillis == endOfMonth.timeInMillis) {
                val dayOfWeek = checkCalTotal.get(Calendar.DAY_OF_WEEK)
                val holiday = HolidayManager.getHolidayName(checkCalTotal)
                if (dayOfWeek != Calendar.SATURDAY && dayOfWeek != Calendar.SUNDAY && holiday == null) {
                    totalWorkDaysInMonth++
                }
                checkCalTotal.add(Calendar.DAY_OF_MONTH, 1)
            }

            // --- NYTT: Räknar bort röda dagar från förväntad tid fram till idag ---
            for (i in 1..currentDayOfMonth) {
                val dayOfWeek = checkCal.get(Calendar.DAY_OF_WEEK)
                val holiday = HolidayManager.getHolidayName(checkCal)
                if (dayOfWeek != Calendar.SATURDAY && dayOfWeek != Calendar.SUNDAY && holiday == null) {
                    workDaysUntilToday++
                }
                checkCal.add(Calendar.DAY_OF_MONTH, 1)
            }

            val totalGoalMinForMonth = if (smartHelpMode == 2) {
                consultantGoalMinutes
            } else {
                totalWorkDaysInMonth * dailyGoalAfterLunch
            }

            val expectedUntilToday = if (smartHelpMode == 2) {
                val avgDailyPace = totalGoalMinForMonth / Math.max(1, totalWorkDaysInMonth)
                workDaysUntilToday * avgDailyPace
            } else {
                workDaysUntilToday * dailyGoalAfterLunch
            }

            var actualMs = 0L
            val monthlyLogs = logs.filter { it.timestamp >= startOfMonth.timeInMillis }.sortedBy { it.timestamp }
            var tempInTs = 0L
            var isTrackingIn = false
            for (log in monthlyLogs) {
                if (log.type == WorkLog.TYPE_IN) { tempInTs = log.timestamp; isTrackingIn = true }
                else if (log.type.startsWith(WorkLog.TYPE_OUT) && isTrackingIn) { actualMs += (log.timestamp - tempInTs); isTrackingIn = false }
            }
            if (isCurrentlyIn && lastInLog != null && lastInLog.timestamp >= startOfMonth.timeInMillis) {
                actualMs += (System.currentTimeMillis() - lastInLog.timestamp)
            }

            val actualMin = (actualMs / 60000).toInt()
            val balance = actualMin - expectedUntilToday

            val monthlyProgressBarProgress = if (totalGoalMinForMonth > 0) (actualMin.toFloat() / totalGoalMinForMonth.toFloat() * 100f).toInt() else 0
            val expectedPercentOfTotalMonth = if (totalGoalMinForMonth > 0) expectedUntilToday.toFloat() / totalGoalMinForMonth.toFloat() else 0f
            val expectedPct = expectedPercentOfTotalMonth * 100f

            val progressColor = if (balance >= 0) Color.parseColor("#4CAF50") else Color.parseColor("#F3AD5A")
            val absBal = Math.abs(balance)

            val prefix = if (balance >= 0) "+" else "-"
            val monthBalanceText = context.getString(R.string.month_balance_format, prefix, absBal / 60, absBal % 60)

            val lastOutType = lastLogOverall?.type ?: ""
            val toggleStatusTextStr = if (isCurrentlyIn) {
                val showAsManual = lastInLog?.ssid?.startsWith("Manuell") == true && !isWifiConnected
                if (showAsManual) context.getString(R.string.toggle_in_manual) else context.getString(R.string.toggle_in_auto)
            } else {
                when {
                    lastOutType.contains("Auto", ignoreCase = true) || (wifiCountdownSeconds == -1 && !isWifiConnected && lastOutType.startsWith(WorkLog.TYPE_OUT)) -> {
                        context.getString(R.string.toggle_out_auto)
                    }
                    lastOutType.contains("Manuell", ignoreCase = true) -> context.getString(R.string.toggle_out_manual)
                    else -> context.getString(R.string.toggle_out_default)
                }
            }
            val toggleStatusColor = if (isCurrentlyIn) Color.parseColor("#4CAF50") else Color.parseColor("#757575")

            _uiState.value = MainUiState(
                currentDate = currentDateText,
                isNextDayClickable = isNextDayClickable,
                nextDayAlpha = nextDayAlpha,
                centerTime = centerTimeText,
                centerTimeColor = centerTimeColor,
                isCountdownVisible = isCountdownVisible,
                currentSsid = currentSsidText,
                currentWorkplace = currentWorkplaceText,
                inTimeCircle = inTimeCircleText,
                statusMessage = statusTextStr,
                statusColor = statusTextColor,
                workProgress = workProgressBarProgress,
                isSecondsVisible = isSecondsVisible,
                monthBalance = monthBalanceText,
                monthProgress = monthlyProgressBarProgress,
                goalLeftWeight = expectedPct,
                goalRightWeight = 100f - expectedPct,
                monthProgressColor = progressColor,
                isCurrentlyIn = isCurrentlyIn,
                toggleStatusText = toggleStatusTextStr,
                toggleStatusColor = toggleStatusColor
            )
        }
    }

    fun handleManualToggle() {
        viewModelScope.launch {
            val lastLog = repository.getLastLog()
            val isCurrentlyIn = lastLog?.type == WorkLog.TYPE_IN
            val now = System.currentTimeMillis()

            if (isCurrentlyIn && lastLog != null) {
                if (now - lastLog.timestamp < 120000L) {
                    repository.deleteLog(lastLog)
                    repository.setManualOverride(false)
                    refreshData(0, -1)
                    return@launch
                }

                val inTime = lastLog.timestamp
                val ssid = lastLog.ssid ?: "Manuell"

                insertManualLogoutWithMidnightSplit(inTime, now, ssid)
                repository.setManualOverride(true)
            } else {
                if (lastLog != null && lastLog.type.startsWith(WorkLog.TYPE_OUT)) {
                    val timeSinceLogout = now - lastLog.timestamp
                    if (timeSinceLogout < 120000L) {
                        repository.deleteLog(lastLog)
                        repository.setManualOverride(false)
                        refreshData(0, -1)
                        return@launch
                    }
                }

                var workplaceName = "Manuell"
                val prefs = context.getSharedPreferences("arbetslogg_prefs", Context.MODE_PRIVATE)
                val savedData = prefs.getString("workplaces_structure", "") ?: ""

                if (savedData.isNotEmpty()) {
                    val blocks = savedData.split("|")
                    var matchedName: String? = null

                    try {
                        val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                        val info = wifiManager.connectionInfo
                        val currentSsid = info?.ssid?.replace("\"", "")?.trim() ?: ""

                        for (block in blocks) {
                            val parts = block.split(":")
                            if (parts.size == 2) {
                                val name = parts[0]
                                val ssids = parts[1].split(",").map { it.trim() }
                                if (currentSsid.isNotEmpty() && ssids.any { it.equals(currentSsid, ignoreCase = true) }) {
                                    matchedName = name
                                    break
                                }
                            }
                        }
                    } catch (e: Exception) {}

                    if (matchedName == null && blocks.size == 1) {
                        val parts = blocks[0].split(":")
                        if (parts.size == 2) matchedName = parts[0]
                    }

                    if (matchedName != null) {
                        workplaceName = "Manuell - $matchedName"
                    }
                }

                repository.insertLog(WorkLog(type = WorkLog.TYPE_IN, timestamp = now, ssid = workplaceName))
                repository.setManualOverride(false)
            }
            refreshData(0, -1)
        }
    }

    private suspend fun insertManualLogoutWithMidnightSplit(inTime: Long, outTime: Long, ssid: String) {
        val inCal = Calendar.getInstance().apply { timeInMillis = inTime }
        val outCal = Calendar.getInstance().apply { timeInMillis = outTime }

        if (inCal.get(Calendar.YEAR) == outCal.get(Calendar.YEAR) &&
            inCal.get(Calendar.DAY_OF_YEAR) == outCal.get(Calendar.DAY_OF_YEAR)) {
            repository.insertLog(WorkLog(type = WorkLog.TYPE_OUT_MANUAL, timestamp = outTime, ssid = ssid))
        } else {
            val currentStart = inCal.clone() as Calendar
            while (currentStart.get(Calendar.YEAR) < outCal.get(Calendar.YEAR) ||
                (currentStart.get(Calendar.YEAR) == outCal.get(Calendar.YEAR) &&
                        currentStart.get(Calendar.DAY_OF_YEAR) < outCal.get(Calendar.DAY_OF_YEAR))) {

                val endOfDay = (currentStart.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, 23)
                    set(Calendar.MINUTE, 59)
                    set(Calendar.SECOND, 59)
                    set(Calendar.MILLISECOND, 999)
                }
                repository.insertLog(WorkLog(type = WorkLog.TYPE_OUT_MANUAL, timestamp = endOfDay.timeInMillis, ssid = ssid))

                currentStart.add(Calendar.DAY_OF_YEAR, 1)
                currentStart.apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                repository.insertLog(WorkLog(type = WorkLog.TYPE_IN, timestamp = currentStart.timeInMillis, ssid = ssid))
            }
            repository.insertLog(WorkLog(type = WorkLog.TYPE_OUT_MANUAL, timestamp = outTime, ssid = ssid))
        }
    }
}

// ==========================================
// --- NYTT: AUTOMATISK HELGDAGSMOTOR ---
// ==========================================
object HolidayManager {

    // Räknar ut påskdagen matematiskt för valfritt år (Anonymous Gregorian Algorithm)
    private fun getEasterSunday(year: Int): Calendar {
        val a = year % 19
        val b = year / 100
        val c = year % 100
        val d = b / 4
        val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4
        val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = ((h + l - 7 * m + 114) % 31) + 1

        return Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month - 1)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
    }

    // Returnerar namnet på helgdagen, eller null om det är en vanlig dag
    fun getHolidayName(cal: Calendar): String? {
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH) + 1 // Jan = 1, Dec = 12
        val day = cal.get(Calendar.DAY_OF_MONTH)
        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)

        // 1. Fasta röda dagar samt de tre stora aftnarna
        if (month == 1 && day == 1) return "Nyårsdagen"
        if (month == 1 && day == 6) return "Trettondedag jul"
        if (month == 5 && day == 1) return "Första maj"
        if (month == 6 && day == 6) return "Sveriges nationaldag"
        if (month == 12 && day == 24) return "Julafton"
        if (month == 12 && day == 25) return "Juldagen"
        if (month == 12 && day == 26) return "Annandag jul"
        if (month == 12 && day == 31) return "Nyårsafton"

        // 2. Rörliga dagar baserade på Påsken
        val easter = getEasterSunday(year)
        val checkMs = Calendar.getInstance().apply {
            timeInMillis = cal.timeInMillis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        // Långfredagen (Påsk - 2 dagar)
        val goodFriday = (easter.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -2) }
        if (checkMs == goodFriday.timeInMillis) return "Långfredagen"

        // Påskdagen
        if (checkMs == easter.timeInMillis) return "Påskdagen"

        // Annandag påsk (Påsk + 1 dag)
        val easterMonday = (easter.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
        if (checkMs == easterMonday.timeInMillis) return "Annandag påsk"

        // Kristi himmelsfärd (Påsk + 39 dagar)
        val ascension = (easter.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 39) }
        if (checkMs == ascension.timeInMillis) return "Kristi himmelsfärdsdag"

        // 3. Övriga rörliga svenska helgmarkerade dagar
        // Midsommarafton: Fredagen mellan 19 juni och 25 juni
        if (month == 6 && dayOfWeek == Calendar.FRIDAY && day in 19..25) return "Midsommarafton"
        // Midsommardagen: Lördagen mellan 20 juni och 26 juni
        if (month == 6 && dayOfWeek == Calendar.SATURDAY && day in 20..26) return "Midsommardagen"

        return null
    }

    // Mappar helgdag till dina unika och peppande hälsningar
// Mappar helgdag till rätt textresurs i strings.xml
    fun getGreetingResId(holidayName: String): Int {
        return when (holidayName) {
            "Nyårsdagen" -> R.string.holiday_nyarsdagen
            "Trettondedag jul" -> R.string.holiday_trettondedag_jul
            "Långfredagen" -> R.string.holiday_langfredagen
            "Påskdagen", "Annandag påsk" -> R.string.holiday_pask
            "Första maj" -> R.string.holiday_forsta_maj
            "Kristi himmelsfärdsdag" -> R.string.holiday_kristi_himmelsfard
            "Sveriges nationaldag" -> R.string.holiday_nationaldag
            "Midsommarafton", "Midsommardagen" -> R.string.holiday_midsommar
            "Julafton", "Juldagen", "Annandag jul" -> R.string.holiday_jul
            "Nyårsafton" -> R.string.holiday_nyarsafton
            else -> 0 // 0 betyder att vi använder fallback-formatet
        }
    }
}

data class MainUiState(
    val currentDate: String = "",
    val isNextDayClickable: Boolean = false,
    val nextDayAlpha: Float = 0.3f,
    val centerTime: String = "00:00",
    val centerTimeColor: Int = Color.parseColor("#4CAF50"),
    val isCountdownVisible: Boolean = false,
    val currentSsid: String = "---",
    val currentWorkplace: String = "",
    val inTimeCircle: String = "---",
    val statusMessage: String = "",
    val statusColor: Int = Color.GRAY,
    val workProgress: Int = 0,
    val isSecondsVisible: Boolean = false,
    val monthBalance: String = "+0h 00m",
    val monthProgress: Int = 0,
    val goalLeftWeight: Float = 0f,
    val goalRightWeight: Float = 100f,
    val monthProgressColor: Int = Color.parseColor("#4CAF50"),
    val toggleStatusText: String = "UTCHECKAD",
    val toggleStatusColor: Int = Color.GRAY,
    val isCurrentlyIn: Boolean = false
)