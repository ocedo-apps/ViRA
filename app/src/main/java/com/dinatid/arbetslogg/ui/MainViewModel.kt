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

            // --- TYPSÄKERHET FIXAD HÄR ---
            val isCurrentlyIn = lastLogOverall?.type == WorkLog.TYPE_IN
            val lastInLog = logs.filter { it.type == WorkLog.TYPE_IN }.maxByOrNull { it.timestamp }

            if (dayLogs.isNotEmpty()) {
                var lastInTs = 0L; var isIn = false
                for (l in dayLogs) {
                    // --- TYPSÄKERHET FIXAD HÄR ---
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
            var statusTextStr: String
            var statusTextColor: Int

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
                } else {
                    statusTextStr = context.getString(R.string.status_logged_time)
                    statusTextColor = Color.GRAY
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
            while (checkCalTotal.before(endOfMonth) || checkCalTotal.timeInMillis == endOfMonth.timeInMillis) {
                val dayOfWeek = checkCalTotal.get(Calendar.DAY_OF_WEEK)
                if (dayOfWeek != Calendar.SATURDAY && dayOfWeek != Calendar.SUNDAY) totalWorkDaysInMonth++
                checkCalTotal.add(Calendar.DAY_OF_MONTH, 1)
            }

            for (i in 1..currentDayOfMonth) {
                val dayOfWeek = checkCal.get(Calendar.DAY_OF_WEEK)
                if (dayOfWeek != Calendar.SATURDAY && dayOfWeek != Calendar.SUNDAY) workDaysUntilToday++
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
                // --- TYPSÄKERHET FIXAD HÄR ---
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
            // --- TYPSÄKERHET FIXAD HÄR ---
            val isCurrentlyIn = lastLog?.type == WorkLog.TYPE_IN
            val now = System.currentTimeMillis()

            if (isCurrentlyIn && lastLog != null) {
                // --- CHECKAR UT MANUELLT ---

                // SPÄRR 1: Råkade jag checka in för mindre än 2 minuter sen?
                if (now - lastLog.timestamp < 120000L) {
                    repository.deleteLog(lastLog)
                    repository.setManualOverride(false)
                    refreshData(0, -1)
                    return@launch
                }

                val inTime = lastLog.timestamp
                val ssid = lastLog.ssid ?: "Manuell"

                // --- STÄDNING (Punkt 3): Hela midnattssplitten körs nu snyggt via vår privata hjälpmetod ---
                insertManualLogoutWithMidnightSplit(inTime, now, ssid)
                repository.setManualOverride(true)
            } else {
                // --- CHECKAR IN MANUELLT ---

                // SPÄRR 2: Var förra loggen en utstämpling för mindre än 2 minuter sedan?
                // --- TYPSÄKERHET FIXAD HÄR ---
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

                // --- TYPSÄKERHET FIXAD HÄR ---
                repository.insertLog(WorkLog(type = WorkLog.TYPE_IN, timestamp = now, ssid = workplaceName))
                repository.setManualOverride(false)
            }
            refreshData(0, -1)
        }
    }

    // --- PRIVAT HJÄLPMETOD (Punkt 3): Isolerar midnattssplitten för en renare arkitektur ---
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