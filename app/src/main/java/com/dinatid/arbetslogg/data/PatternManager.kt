package com.dinatid.arbetslogg.data

import com.dinatid.arbetslogg.WorkLog
import java.util.*
import java.util.concurrent.TimeUnit

data class DailyPattern(
    val avgArrivalMins: Int?,
    val avgDepartureMins: Int?,
    val avgLunchStartMins: Int?,
    val avgLunchEndMins: Int?,
    val workProbability: Float // Sannolikhet att man faktiskt jobbar denna veckodag (0.0 - 1.0)
)

data class UserPattern(
    val weekdayPatterns: Map<Int, DailyPattern> // Map från Calendar.WEEKDAY till mönster
)

class PatternManager(private val logs: List<WorkLog>) {

    fun calculatePattern(): UserPattern {
        val patterns = mutableMapOf<Int, DailyPattern>()
        
        // Gruppera loggar per veckodag (måndag-söndag)
        val logsByWeekday = (1..7).associateWith { weekday ->
            logs.filter { 
                val cal = Calendar.getInstance().apply { timeInMillis = it.timestamp }
                cal.get(Calendar.DAY_OF_WEEK) == weekday
            }
        }

        for (weekday in 1..7) {
            val dayLogs = logsByWeekday[weekday] ?: emptyList()
            if (dayLogs.isEmpty()) {
                patterns[weekday] = DailyPattern(null, null, null, null, 0f)
                continue
            }

            val arrivals = mutableListOf<Int>()
            val departures = mutableListOf<Int>()
            val lunchStarts = mutableListOf<Int>()
            val lunchEnds = mutableListOf<Int>()

            // Gruppera per specifik dag (t.ex. alla måndagar separat)
            val specificDays = dayLogs.groupBy { 
                val cal = Calendar.getInstance().apply { timeInMillis = it.timestamp }
                cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR)
            }

            for (logsOnSpecificDay in specificDays.values.map { it.sortedBy { l -> l.timestamp } }) {
                logsOnSpecificDay.firstOrNull { it.type == WorkLog.TYPE_IN }?.let {
                    arrivals.add(getTimeOfDayInMinutes(it.timestamp))
                }
                logsOnSpecificDay.lastOrNull { it.type.startsWith(WorkLog.TYPE_OUT) }?.let {
                    departures.add(getTimeOfDayInMinutes(it.timestamp))
                }
                
                var lastOutTs = 0L
                for (log in logsOnSpecificDay) {
                    if (log.type.startsWith(WorkLog.TYPE_OUT)) {
                        lastOutTs = log.timestamp
                    } else if (log.type == WorkLog.TYPE_IN && lastOutTs != 0L) {
                        val gapMin = (log.timestamp - lastOutTs) / 60000
                        val hour = Calendar.getInstance().apply { timeInMillis = lastOutTs }.get(Calendar.HOUR_OF_DAY)
                        if (gapMin in 25..90 && hour in 10..13) {
                            lunchStarts.add(getTimeOfDayInMinutes(lastOutTs))
                            lunchEnds.add(getTimeOfDayInMinutes(log.timestamp))
                            break
                        }
                    }
                }
            }

            // Räkna ut hur ofta man jobbar denna dag (baserat på sista 4 veckorna)
            val workProbability = if (specificDays.isNotEmpty()) 1.0f else 0f 
            // I en mer avancerad version kan vi kolla sista 4 måndagarna etc.

            patterns[weekday] = DailyPattern(
                avgArrivalMins = getMedian(arrivals),
                avgDepartureMins = getMedian(departures),
                avgLunchStartMins = getMedian(lunchStarts),
                avgLunchEndMins = getMedian(lunchEnds),
                workProbability = workProbability
            )
        }

        return UserPattern(patterns)
    }

    private fun getTimeOfDayInMinutes(timestamp: Long): Int {
        val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
        return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    }

    private fun getMedian(list: List<Int>): Int? {
        if (list.isEmpty()) return null
        val sorted = list.sorted()
        return if (sorted.size % 2 == 0) {
            (sorted[sorted.size / 2] + sorted[sorted.size / 2 - 1]) / 2
        } else {
            sorted[sorted.size / 2]
        }
    }
}