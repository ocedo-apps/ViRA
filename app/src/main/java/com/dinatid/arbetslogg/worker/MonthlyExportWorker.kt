package com.dinatid.arbetslogg.worker

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.dinatid.arbetslogg.R
import com.dinatid.arbetslogg.WorkLog
import com.dinatid.arbetslogg.data.TimeRepository
import com.dinatid.arbetslogg.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@SuppressLint("NewApi")
class MonthlyExportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val repository = TimeRepository.getInstance(context)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val now = Calendar.getInstance()
        if (now.get(Calendar.DAY_OF_MONTH) != 1) {
            Log.d("MonthlyExportWorker", "Inte den 1:a i månaden. Avbryter.")
            return@withContext Result.success()
        }
        
        Log.d("MonthlyExportWorker", "Startar automatisk månads-export...")
        
        try {
            // Vi vill ha förra månaden
            val lastMonth = (now.clone() as Calendar).apply {
                add(Calendar.MONTH, -1)
                set(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
            }
            
            val monthName = SimpleDateFormat("MMMM", Locale("sv", "SE")).format(lastMonth.time).replaceFirstChar { it.uppercase() }
            val year = lastMonth.get(Calendar.YEAR)
            
            val logs = repository.getLogsInTimeRange(lastMonth.timeInMillis, lastMonth.timeInMillis + (31L * 86400000L))
            // Filtrera logs så vi bara får de som faktiskt tillhör rätt månad (ifall tidsspannet blev för stort)
            val filteredLogs = logs.filter { 
                val c = Calendar.getInstance().apply { timeInMillis = it.timestamp }
                c.get(Calendar.MONTH) == lastMonth.get(Calendar.MONTH) && c.get(Calendar.YEAR) == lastMonth.get(Calendar.YEAR)
            }

            if (filteredLogs.isEmpty()) {
                Log.i("MonthlyExportWorker", "Inga loggar hittades för $monthName. Skippar export.")
                return@withContext Result.success()
            }

            // Hämta arbetsplatser för att veta vilka rapporter som ska skapas
            val prefs = applicationContext.getSharedPreferences("arbetslogg_prefs", Context.MODE_PRIVATE)
            val savedWorkplaces = prefs.getString("workplaces_structure", "") ?: ""
            
            if (savedWorkplaces.isEmpty()) return@withContext Result.success()

            val workplaceNames = savedWorkplaces.split("|").map { it.split(":")[0] }.filter { it.isNotEmpty() }
            val generatedFiles = mutableListOf<File>()

            for (name in workplaceNames) {
                val file = generateCsvForWorkplace(name, filteredLogs, lastMonth.timeInMillis, savedWorkplaces)
                if (file != null) generatedFiles.add(file)
            }

            if (generatedFiles.isNotEmpty()) {
                sendSuccessNotification(monthName)
            }

            return@withContext Result.success()
        } catch (e: Exception) {
            Log.e("MonthlyExportWorker", "Export misslyckades: ${e.message}")
            return@withContext Result.failure()
        }
    }

    private fun generateCsvForWorkplace(workplaceName: String, logs: List<WorkLog>, startTimeMs: Long, savedWorkplaces: String): File? {
        val dailyHours = HashMap<String, Double>()
        val sortedLogs = logs.sortedBy { it.timestamp }
        var currentInLog: WorkLog? = null
        val roundInterval = repository.getRoundingInterval()

        for (log in sortedLogs) {
            if (log.type == "IN") {
                currentInLog = log
            } else if (log.type.startsWith("UT") && currentInLog != null) {
                val rawSsid = currentInLog.ssid
                val (wpName, _) = getProperWorkplaceName(rawSsid, savedWorkplaces)

                if (wpName.equals(workplaceName, ignoreCase = true)) {
                    val roundedIn = repository.roundTimestamp(currentInLog.timestamp, roundInterval)
                    val roundedOut = repository.roundTimestamp(log.timestamp, roundInterval)

                    val durationMs = roundedOut - roundedIn
                    val durationHours = durationMs / 3600000.0

                    val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(roundedIn))
                    dailyHours[dateStr] = (dailyHours[dateStr] ?: 0.0) + durationHours
                }
                currentInLog = null
            }
        }

        if (dailyHours.isEmpty()) return null

        val csv = StringBuilder()
        val monthStr = SimpleDateFormat("MMMM", Locale("sv", "SE")).format(startTimeMs).replaceFirstChar { it.uppercase() }
        val yearStr = SimpleDateFormat("yyyy", Locale("sv", "SE")).format(startTimeMs)

        csv.append(";;;;;;;Tidrapport;;;;\n")
        csv.append(";;;;;;;ÅR;MÅNAD;;;\n")
        csv.append(";$workplaceName;;;;;;$yearStr;$monthStr;;;\n")
        csv.append(";\"Auto-genererad månadsrapport\";;;;;;;;;;\n")
        csv.append("Datum;Veckodag;Arbetade timmar;Varav övertid;Komptid;Sjuk;Semester/ATK;VAB/för.ledig\n")

        val cal = Calendar.getInstance().apply { timeInMillis = startTimeMs }
        val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val dayFormat = SimpleDateFormat("EEEE", Locale("sv", "SE"))

        for (i in 0 until daysInMonth) {
            val dateStr = dateFormat.format(cal.time)
            val hours = dailyHours[dateStr] ?: 0.0
            val hoursStr = if (hours > 0) String.format(Locale("sv", "SE"), "%.2f", hours) else ""
            csv.append("$dateStr;${dayFormat.format(cal.time)};$hoursStr;;;;;\n")
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }

        val safeName = workplaceName.replace(Regex("[^a-zA-Z0-9åäöÅÄÖ]"), "_")
        val fileName = "Auto_Tidrapport_${safeName}_$monthStr.csv"
        val file = File(applicationContext.filesDir, fileName)
        file.writeText(csv.toString(), Charsets.ISO_8859_1)
        return file
    }

    private fun getProperWorkplaceName(rawSsid: String, savedWorkplaces: String): Pair<String, Boolean> {
        if (rawSsid.startsWith("Manuell", ignoreCase = true)) {
            val clean = if (rawSsid.contains("-")) rawSsid.substringAfter("-").trim() else "Övrigt"
            return Pair(clean, true)
        }
        val blocks = savedWorkplaces.split("|")
        for (block in blocks) {
            val parts = block.split(":")
            if (parts.size == 2) {
                val name = parts[0]
                val ssids = parts[1].split(",").map { it.trim() }
                if (ssids.any { it.equals(rawSsid, ignoreCase = true) }) return Pair(name, false)
            }
        }
        return Pair(rawSsid, false)
    }

    private fun sendSuccessNotification(monthName: String) {
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("open_tab", 2) // Öppna rapporter
        }
        val pi = PendingIntent.getActivity(applicationContext, 100, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val channelId = "export_channel"
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        nm.createNotificationChannel(NotificationChannel(channelId, "Export", NotificationManager.IMPORTANCE_DEFAULT))

        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle("Månadsrapport klar! ✅")
            .setContentText("Tidrapporterna för $monthName har genererats automatiskt.")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()

        nm.notify(100, notification)
    }
}
