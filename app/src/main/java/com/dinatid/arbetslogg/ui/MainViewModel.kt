package com.dinatid.arbetslogg.ui

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.graphics.Color
import android.os.Build
import androidx.core.content.ContextCompat
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

@SuppressLint("NewApi")
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = TimeRepository.getInstance(application)
    private val context = application.applicationContext

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    fun refreshData(selectedDateOffset: Int, wifiCountdownSeconds: Int) {
        viewModelScope.launch {
            val hasLocation = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val hasNotifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
            } else true

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

            val lastLogOverall = logs.lastOrNull() // logs is already sorted by timestamp? 
            // Wait, repository.getAllLogs() returns ORDER BY timestamp ASC according to the DAO.
            // So logs is already sorted.
            
            val isCurrentlyIn = lastLogOverall?.type == WorkLog.TYPE_IN
            val lastInLog = logs.lastOrNull { it.type == WorkLog.TYPE_IN }

            val dayMin = calculateWorkMinutes(logs, startOfDay.timeInMillis, startOfDay.timeInMillis + 86400000)

            val currentDateText = if (selectedDateOffset == 0) {
                context.getString(R.string.date_today)
            } else if (selectedDateOffset == -1) {
                context.getString(R.string.date_yesterday)
            } else {
                SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(cal.time).uppercase()
            }

            val nextDayAlpha = if (selectedDateOffset == 0) 0.3f else 1.0f
            val isNextDayClickable = selectedDateOffset != 0

            val currentTheme = repository.getAppTheme()
            val themeColor = if (currentTheme == 1) Color.WHITE else Color.parseColor("#FCDEBB")
            val greenColor = if (currentTheme == 1) ContextCompat.getColor(context, R.color.modern_accent_blue) else ContextCompat.getColor(context, R.color.status_green)
            
            val typedValueAccent = android.util.TypedValue()
            context.theme.resolveAttribute(R.attr.accentColorCustom, typedValueAccent, true)
            val accentColor = typedValueAccent.data

            var centerTimeText: String
            var centerTimeColor: Int
            var isCountdownVisible = false
            var currentSsidText: String
            var currentWorkplaceText = ""
            var inTimeCircleText: String
            var statusTextStr = ""
            var statusTextColor: Int

            val holidayNameOnSelectedDay = HolidayManager.getHolidayName(cal)

            if (wifiCountdownSeconds >= 0 && selectedDateOffset == 0 && isCurrentlyIn) {
                centerTimeText = String.format(Locale.getDefault(), "%02d:%02d", wifiCountdownSeconds / 60, wifiCountdownSeconds % 60)
                centerTimeColor = Color.parseColor("#F44336")
                isCountdownVisible = true
                currentSsidText = context.getString(R.string.status_logging_out_prefix)
                currentWorkplaceText = ""
                inTimeCircleText = context.getString(R.string.empty_value)
                statusTextStr = context.getString(R.string.warning_outside_zone)
                statusTextColor = Color.parseColor("#F44336")
            } else {
                centerTimeText = String.format(Locale.getDefault(), "%02d:%02d", dayMin / 60, dayMin % 60)
                centerTimeColor = themeColor

                if (isCurrentlyIn && lastInLog != null) {
                    val rawSsid = lastInLog.ssid
                    val isManual = rawSsid.startsWith("Manuell", ignoreCase = true)

                    currentSsidText = context.getString(if (isManual) R.string.status_manual_in else R.string.status_auto_in)
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
                        val rawSsid = lastLogOverall.ssid

                        val isManualOut = outType.contains("Manuell", ignoreCase = true)
                        currentSsidText = context.getString(if (isManualOut) R.string.status_manual_out else R.string.status_auto_out)

                        currentWorkplaceText = when {
                            rawSsid.contains("-") -> rawSsid.substringAfter("-").trim()
                            rawSsid.equals("Manuell", ignoreCase = true) -> ""
                            else -> rawSsid
                        }

                        val timeFormatted = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(lastLogOverall.timestamp))
                        inTimeCircleText = context.getString(R.string.circle_out_time, timeFormatted)
                    } else if (selectedDateOffset == 0) {
                        currentSsidText = context.getString(R.string.status_checked_out)
                        currentWorkplaceText = ""
                        inTimeCircleText = context.getString(R.string.empty_value)
                    } else {
                        currentSsidText = context.getString(R.string.empty_value)
                        currentWorkplaceText = ""
                        inTimeCircleText = context.getString(R.string.empty_value)
                    }
                }

                if (selectedDateOffset == 0) {
                    val progressPercent = if (dailyGoalTotalMin > 0) (dayMin.toFloat() / dailyGoalTotalMin.toFloat() * 100f).toInt() else 0

                    statusTextStr = when {
                        holidayNameOnSelectedDay != null && !isCurrentlyIn && dayMin == 0 -> {
                            val resId = HolidayManager.getGreetingResId(holidayNameOnSelectedDay)
                            if (resId != 0) {
                                context.getString(resId)
                            } else {
                                context.getString(R.string.holiday_default_format, holidayNameOnSelectedDay.uppercase(Locale.getDefault()))
                            }
                        }
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
                    
                    // Om vi är utcheckade i klassiskt tema, använd den orangea färgen
                    statusTextColor = if (!isCurrentlyIn && dayMin > 0 && currentTheme == 0) {
                        accentColor
                    } else {
                        themeColor
                    }
                } else {
                    statusTextColor = themeColor
                }
            }

            val workProgressBarProgress = if (dailyGoalTotalMin > 0) (dayMin.toFloat() / dailyGoalTotalMin.toFloat() * 100f).toInt() else 0
            val isSecondsVisible = selectedDateOffset == 0 && wifiCountdownSeconds < 0

            val now = Calendar.getInstance()
            val startOfMonth = (now.clone() as Calendar).apply {
                set(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }

            var workDaysUntilToday = 0
            var totalWorkDaysInMonth = 0
            val todayDayOfMonth = now.get(Calendar.DAY_OF_MONTH)
            val monthDaysCount = now.getActualMaximum(Calendar.DAY_OF_MONTH)
            val checkCal = startOfMonth.clone() as Calendar

            for (i in 1..monthDaysCount) {
                val dayOfWeek = checkCal.get(Calendar.DAY_OF_WEEK)
                val holiday = HolidayManager.getHolidayName(checkCal)
                val isWorkDay = dayOfWeek != Calendar.SATURDAY && dayOfWeek != Calendar.SUNDAY && holiday == null
                if (isWorkDay) {
                    totalWorkDaysInMonth++
                    if (i <= todayDayOfMonth) workDaysUntilToday++
                }
                checkCal.add(Calendar.DAY_OF_MONTH, 1)
            }

            val totalGoalMinForMonth = if (smartHelpMode == 2) {
                consultantGoalMinutes
            } else {
                totalWorkDaysInMonth * dailyGoalTotalMin
            }

            val expectedUntilNow = if (smartHelpMode == 2) {
                val avgDailyPace = totalGoalMinForMonth.toDouble() / Math.max(1, totalWorkDaysInMonth)
                (workDaysUntilToday * avgDailyPace).toInt()
            } else {
                workDaysUntilToday * dailyGoalTotalMin
            }

            val actualMin = calculateWorkMinutes(logs, startOfMonth.timeInMillis, System.currentTimeMillis(), lunchMin)
            val balance = actualMin - expectedUntilNow

            val monthlyProgressBarProgress = if (totalGoalMinForMonth > 0) (actualMin.toFloat() / totalGoalMinForMonth.toFloat() * 100f).toInt() else 0
            
            // Mållinjen visar var man borde vara just nu (inklusive dagens mål)
            val expectedPercentOfTotalMonth = if (totalGoalMinForMonth > 0) expectedUntilNow.toFloat() / totalGoalMinForMonth.toFloat() else 0f
            val expectedPct = expectedPercentOfTotalMonth * 100f

            val progressColor = if (balance >= 0) {
                if (currentTheme == 1) ContextCompat.getColor(context, R.color.modern_status_green) 
                else greenColor
            } else Color.parseColor("#F3AD5A")
            val absBal = Math.abs(balance)

            val prefix = if (balance >= 0) "+" else "-"
            val monthBalanceText = context.getString(R.string.month_balance_format, prefix, absBal / 60, absBal % 60)

            val lastOutType = lastLogOverall?.type ?: ""
            val isManualOutLast = lastOutType.contains("Manuell", ignoreCase = true)

            val toggleStatusTextStr = if (isCurrentlyIn) {
                val showAsManual = lastInLog?.ssid?.startsWith("Manuell") == true && !isWifiConnected
                if (showAsManual) context.getString(R.string.toggle_in_manual) else context.getString(R.string.toggle_in_auto)
            } else {
                when {
                    isManualOutLast -> context.getString(R.string.toggle_out_manual)
                    lastOutType.contains("Auto", ignoreCase = true) || (wifiCountdownSeconds == -1 && !isWifiConnected && lastOutType.startsWith(WorkLog.TYPE_OUT)) -> {
                        context.getString(R.string.toggle_out_auto)
                    }
                    else -> context.getString(R.string.toggle_out_default)
                }
            }
            val toggleStatusColor = if (isCurrentlyIn) greenColor else themeColor

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
                toggleStatusColor = toggleStatusColor,
                isLocationPermissionMissing = !hasLocation,
                isNotificationPermissionMissing = !hasNotifications
            )
        }
    }

    // HÄR VAR FELET: Funktionen tar nu emot 'chosenWorkplace' på rätt sätt!
    fun handleManualToggle(chosenWorkplace: String?) {
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

                repository.insertLogoutWithMidnightSplit(inTime, now, ssid, true)
                repository.setManualOverride(true)
                repository.setManualOverrideSsid(repository.getCurrentSsid())
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

                val workplaceName = if (!chosenWorkplace.isNullOrEmpty()) {
                    "Manuell - $chosenWorkplace"
                } else {
                    "Manuell"
                }

                repository.insertLog(WorkLog(type = WorkLog.TYPE_IN, timestamp = now, ssid = workplaceName))
                repository.setManualOverride(false)
            }
            refreshData(0, -1)
        }
    }

    private fun calculateWorkMinutes(allLogs: List<WorkLog>, startTs: Long, endTs: Long, lunchToDeduct: Int = 0): Int {
        val rangeLogs = allLogs.filter { it.timestamp in startTs..endTs }
        val lastLogBeforeStart = allLogs.lastOrNull { it.timestamp < startTs }
        if (rangeLogs.isEmpty() && lastLogBeforeStart?.type != WorkLog.TYPE_IN) return 0

        // Gruppera loggar per dag
        val logsByDay = rangeLogs.groupBy {
            val c = Calendar.getInstance().apply { timeInMillis = it.timestamp }
            "${c.get(Calendar.YEAR)}-${c.get(Calendar.DAY_OF_YEAR)}"
        }

        var totalMonthMinutes = 0
        val now = System.currentTimeMillis()
        val effectiveEndRange = Math.min(now, endTs)

        // Loopa igenom varje kalenderdag i spannet
        val cal = Calendar.getInstance().apply { timeInMillis = startTs }
        val endCal = Calendar.getInstance().apply { timeInMillis = effectiveEndRange }

        var currentlyIn = lastLogBeforeStart?.type == WorkLog.TYPE_IN
        var currentInTs = if (currentlyIn) startTs else 0L

        while (cal.before(endCal) || isSameDay(cal, endCal)) {
            val dayKey = "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.DAY_OF_YEAR)}"
            val dayLogs = logsByDay[dayKey] ?: emptyList()
            
            var dayRawMin = 0
            var largestGapMin = 0
            var lastOutForGap = 0L

            val dayStartTs = cal.apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
            val dayEndTs = cal.apply { set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999) }.timeInMillis
            
            // Om vi kom in i dagen som redan inloggad
            if (currentlyIn) {
                currentInTs = Math.max(dayStartTs, startTs)
            }

            for (log in dayLogs) {
                if (log.type == WorkLog.TYPE_IN && !currentlyIn) {
                    currentInTs = log.timestamp
                    currentlyIn = true
                    
                    if (lastOutForGap != 0L) {
                        val gap = ((log.timestamp - lastOutForGap) / 60000).toInt()
                        if (gap > largestGapMin) largestGapMin = gap
                    }
                } else if (log.type.startsWith(WorkLog.TYPE_OUT) && currentlyIn) {
                    dayRawMin += ((log.timestamp - currentInTs) / 60000).toInt()
                    currentlyIn = false
                    lastOutForGap = log.timestamp
                }
            }

            if (currentlyIn) {
                val effectiveDayEnd = Math.min(effectiveEndRange, dayEndTs)
                if (effectiveDayEnd > currentInTs) {
                    dayRawMin += ((effectiveDayEnd - currentInTs) / 60000).toInt()
                }
            }

            // Smart Lunch
            var actualDeduct = 0
            if (dayRawMin > 300 && largestGapMin < 20) {
                actualDeduct = lunchToDeduct
            }
            totalMonthMinutes += Math.max(0, dayRawMin - actualDeduct)

            // Gå till nästa dag
            cal.timeInMillis = dayStartTs
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }

        return totalMonthMinutes
    }

    private fun isSameDay(c1: Calendar, c2: Calendar): Boolean {
        return c1.get(Calendar.YEAR) == c2.get(Calendar.YEAR) && 
               c1.get(Calendar.DAY_OF_YEAR) == c2.get(Calendar.DAY_OF_YEAR)
    }
}

object HolidayManager {

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

    fun getHolidayName(cal: Calendar): String? {
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH) + 1
        val day = cal.get(Calendar.DAY_OF_MONTH)
        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)

        if (month == 1 && day == 1) return "Nyårsdagen"
        if (month == 1 && day == 6) return "Trettondedag jul"
        if (month == 5 && day == 1) return "Första maj"
        if (month == 6 && day == 6) return "Sveriges nationaldag"
        if (month == 12 && day == 24) return "Julafton"
        if (month == 12 && day == 25) return "Juldagen"
        if (month == 12 && day == 26) return "Annandag jul"
        if (month == 12 && day == 31) return "Nyårsafton"

        val easter = getEasterSunday(year)
        val checkMs = Calendar.getInstance().apply {
            timeInMillis = cal.timeInMillis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val goodFriday = (easter.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -2) }
        if (checkMs == goodFriday.timeInMillis) return "Långfredagen"

        if (checkMs == easter.timeInMillis) return "Påskdagen"

        val easterMonday = (easter.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
        if (checkMs == easterMonday.timeInMillis) return "Annandag påsk"

        val ascension = (easter.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 39) }
        if (checkMs == ascension.timeInMillis) return "Kristi himmelsfärdsdag"

        if (month == 6 && dayOfWeek == Calendar.FRIDAY && day in 19..25) return "Midsommarafton"
        if (month == 6 && dayOfWeek == Calendar.SATURDAY && day in 20..26) return "Midsommardagen"

        return null
    }

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
            else -> 0
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
    val isCurrentlyIn: Boolean = false,
    val isLocationPermissionMissing: Boolean = false,
    val isNotificationPermissionMissing: Boolean = false
)