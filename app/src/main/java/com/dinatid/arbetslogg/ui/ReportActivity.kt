package com.dinatid.arbetslogg.ui

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.lifecycleScope
import com.dinatid.arbetslogg.R
import com.dinatid.arbetslogg.WorkLog
import com.dinatid.arbetslogg.data.TimeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap

class ReportActivity : AppCompatActivity() {

    private lateinit var repository: TimeRepository

    data class WorkplaceTime(var autoMin: Long = 0, var manualMin: Long = 0) {
        val total get() = autoMin + manualMin
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_report)

        repository = TimeRepository(application)

        val txtWeeklyTotal = findViewById<TextView>(R.id.txtWeeklyTotal)
        val llWeeklyWorkplaces = findViewById<LinearLayout>(R.id.llWeeklyWorkplaces)

        val txtMonthlyTotal = findViewById<TextView>(R.id.txtMonthlyTotal)
        val llMonthlyWorkplaces = findViewById<LinearLayout>(R.id.llMonthlyWorkplaces)

        val btnExportReport = findViewById<Button>(R.id.btnExportReport)

        lifecycleScope.launch {
            val userRoundingInterval = repository.getRoundingInterval()

            val prefs = getSharedPreferences("arbetslogg_prefs", Context.MODE_PRIVATE)
            val savedWorkplaces = prefs.getString("workplaces_structure", "") ?: ""

            val now = Calendar.getInstance()

            val startOfWeek = now.clone() as Calendar
            startOfWeek.firstDayOfWeek = Calendar.MONDAY
            startOfWeek.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            startOfWeek.set(Calendar.HOUR_OF_DAY, 0)
            startOfWeek.set(Calendar.MINUTE, 0)
            startOfWeek.set(Calendar.SECOND, 0)
            startOfWeek.set(Calendar.MILLISECOND, 0)

            val startOfMonth = now.clone() as Calendar
            startOfMonth.set(Calendar.DAY_OF_MONTH, 1)
            startOfMonth.set(Calendar.HOUR_OF_DAY, 0)
            startOfMonth.set(Calendar.MINUTE, 0)
            startOfMonth.set(Calendar.SECOND, 0)
            startOfMonth.set(Calendar.MILLISECOND, 0)

            // --- FIX 1: Hämtar nu ENDAST relevanta loggar direkt via SQLite istället för In-Memory-filtrering ---
            val weeklyLogs = repository.getLogsInTimeRange(startOfWeek.timeInMillis, Long.MAX_VALUE)
            val monthlyLogs = repository.getLogsInTimeRange(startOfMonth.timeInMillis, Long.MAX_VALUE)

            val weeklyBreakdown = calculateTimePerWorkplace(weeklyLogs, userRoundingInterval, savedWorkplaces)
            val monthlyBreakdown = calculateTimePerWorkplace(monthlyLogs, userRoundingInterval, savedWorkplaces)

            val totalWeeklyMinutes = weeklyBreakdown.values.sumOf { it.total }
            txtWeeklyTotal.text = "DENNA VECKA - ${formatMinutes(totalWeeklyMinutes)}"

            populateWorkplaceList(llWeeklyWorkplaces, weeklyBreakdown, weeklyLogs, startOfWeek.timeInMillis, false, savedWorkplaces, userRoundingInterval)

            val totalMonthlyMinutes = monthlyBreakdown.values.sumOf { it.total }
            txtMonthlyTotal.text = "DENNA MÅNAD - ${formatMinutes(totalMonthlyMinutes)}"

            populateWorkplaceList(llMonthlyWorkplaces, monthlyBreakdown, monthlyLogs, startOfMonth.timeInMillis, true, savedWorkplaces, userRoundingInterval)

            // --- RUNTIME PERMISSION FÖR KALENDER ---
            val isCalendarEnabled = prefs.getBoolean("use_calendar_integration", false)
            if (isCalendarEnabled && ContextCompat.checkSelfPermission(this@ReportActivity, android.Manifest.permission.READ_CALENDAR)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                androidx.core.app.ActivityCompat.requestPermissions(this@ReportActivity, arrayOf(android.Manifest.permission.READ_CALENDAR), 101)
            }
        }

        btnExportReport.setOnClickListener {
            Toast.makeText(this, "Exportera alla är under uppbyggnad!", Toast.LENGTH_SHORT).show()
        }

        // --- BOTTENMENY ---
        val btnNavDashboard = findViewById<LinearLayout>(R.id.btnNavDashboard)
        val btnNavHistory = findViewById<LinearLayout>(R.id.btnNavHistory)
        val btnNavReports = findViewById<LinearLayout>(R.id.btnNavReports)
        val btnNavSettings = findViewById<LinearLayout>(R.id.btnNavSettings)

        btnNavDashboard.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
            startActivity(intent)
            overridePendingTransition(0, 0)
            finish()
        }
        btnNavHistory.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
            overridePendingTransition(0, 0)
            finish()
        }
        btnNavReports.setOnClickListener { }
        btnNavSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
            overridePendingTransition(0, 0)
            finish()
        }

        val themeColor = Color.parseColor("#FCDEBB")
        val inactiveColor = Color.parseColor("#80FCDEBB")

        (btnNavDashboard.getChildAt(0) as? ImageView)?.imageTintList = ColorStateList.valueOf(inactiveColor)
        (btnNavDashboard.getChildAt(1) as? TextView)?.setTextColor(inactiveColor)
        (btnNavHistory.getChildAt(0) as? ImageView)?.imageTintList = ColorStateList.valueOf(inactiveColor)
        (btnNavHistory.getChildAt(1) as? TextView)?.setTextColor(inactiveColor)
        (btnNavSettings.getChildAt(0) as? ImageView)?.imageTintList = ColorStateList.valueOf(inactiveColor)
        (btnNavSettings.getChildAt(1) as? TextView)?.setTextColor(inactiveColor)

        (btnNavReports.getChildAt(0) as? ImageView)?.imageTintList = ColorStateList.valueOf(themeColor)
        (btnNavReports.getChildAt(1) as? TextView)?.setTextColor(themeColor)
    }

    private fun getProperWorkplaceName(rawSsid: String, savedWorkplaces: String): Pair<String, Boolean> {
        var isManual = false
        var cleanSsid = rawSsid

        if (rawSsid.startsWith("Manuell", ignoreCase = true)) {
            isManual = true
            cleanSsid = if (rawSsid.contains("-")) rawSsid.substringAfter("-").trim() else "Övrigt"
            return Pair(cleanSsid, isManual)
        }

        if (savedWorkplaces.isNotEmpty()) {
            val blocks = savedWorkplaces.split("|")
            for (block in blocks) {
                val parts = block.split(":")
                if (parts.size == 2) {
                    val name = parts[0]
                    val ssids = parts[1].split(",").map { it.trim() }
                    if (ssids.any { it.equals(rawSsid, ignoreCase = true) }) {
                        return Pair(name, isManual)
                    }
                }
            }
        }
        return Pair(rawSsid.ifEmpty { "Okänd" }, isManual)
    }

    private fun calculateTimePerWorkplace(logs: List<WorkLog>, roundInterval: Int, savedWorkplaces: String): Map<String, WorkplaceTime> {
        val sortedLogs = logs.sortedBy { it.timestamp }
        val breakdown = HashMap<String, WorkplaceTime>()
        var currentInLog: WorkLog? = null

        for (log in sortedLogs) {
            if (log.type == "IN") {
                currentInLog = log
            } else if (log.type.startsWith("UT") && currentInLog != null) {
                // --- FIX 2: Använder nu den centraliserade avrundningsfunktionen från Repositoryt ---
                val roundedIn = repository.roundTimestamp(currentInLog.timestamp, roundInterval)
                val roundedOut = repository.roundTimestamp(log.timestamp, roundInterval)
                val durationMin = (roundedOut - roundedIn) / 60000

                val rawSsid = currentInLog.ssid ?: ""
                val (workplaceName, isManual) = getProperWorkplaceName(rawSsid, savedWorkplaces)

                if (!breakdown.containsKey(workplaceName)) {
                    breakdown[workplaceName] = WorkplaceTime()
                }

                if (isManual) {
                    breakdown[workplaceName]!!.manualMin += durationMin
                } else {
                    breakdown[workplaceName]!!.autoMin += durationMin
                }

                currentInLog = null
            }
        }

        if (currentInLog != null) {
            // --- FIX 2: Använder nu den centraliserade avrundningsfunktionen från Repositoryt ---
            val roundedIn = repository.roundTimestamp(currentInLog.timestamp, roundInterval)
            val roundedOut = repository.roundTimestamp(System.currentTimeMillis(), roundInterval)
            val durationMin = (roundedOut - roundedIn) / 60000

            val rawSsid = currentInLog.ssid ?: ""
            val (workplaceName, isManual) = getProperWorkplaceName(rawSsid, savedWorkplaces)

            if (!breakdown.containsKey(workplaceName)) {
                breakdown[workplaceName] = WorkplaceTime()
            }

            if (isManual) {
                breakdown[workplaceName]!!.manualMin += durationMin
            } else {
                breakdown[workplaceName]!!.autoMin += durationMin
            }
        }

        return breakdown
    }

    private fun populateWorkplaceList(
        container: LinearLayout,
        data: Map<String, WorkplaceTime>,
        sourceLogs: List<WorkLog>,
        startTimeMs: Long,
        isMonthly: Boolean,
        savedWorkplaces: String,
        roundInterval: Int
    ) {
        container.removeAllViews()

        if (data.isEmpty() || data.values.sumOf { it.total } == 0L) {
            val emptyText = TextView(this).apply {
                text = "Inga loggade pass."
                setTextColor(ContextCompat.getColor(context, R.color.sunset_text_main))
                textSize = 14f
                gravity = Gravity.CENTER
                try { typeface = ResourcesCompat.getFont(context, R.font.asap_light) } catch(e: Exception){}
                setPadding(0, 16, 0, 16)
            }
            container.addView(emptyText)
            return
        }

        val headerColor = ContextCompat.getColor(this, R.color.text_main)
        val mutedColor = ContextCompat.getColor(this, R.color.text_dark_gray)
        val buttonBgColor = Color.parseColor("#21160C")
        val whiteTextColor = Color.parseColor("#FFFFFF")
        val density = resources.displayMetrics.density

        for ((workplace, wpTime) in data.entries.sortedByDescending { it.value.total }) {

            val cardBlock = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = ContextCompat.getDrawable(context, R.drawable.history_item_background)
                elevation = 3f * density

                val p = (16 * density).toInt()
                setPadding(p, p, p, p)

                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, (14 * density).toInt())
                }
            }

            val headerView = TextView(this).apply {
                text = "${workplace.uppercase()} - ${formatMinutes(wpTime.total)}"
                setTextColor(headerColor)
                textSize = 16f
                try { typeface = ResourcesCompat.getFont(context, R.font.asap_black) } catch(e: Exception){}
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                setPadding(0, 0, 0, 8)
            }
            cardBlock.addView(headerView)

            if (wpTime.autoMin > 0) {
                val subAuto = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 4, 0, 0)
                }
                val lblAuto = TextView(this).apply {
                    text = "Incheckning AUTO"
                    setTextColor(mutedColor)
                    textSize = 14f
                    try { typeface = ResourcesCompat.getFont(context, R.font.asap_light) } catch(e: Exception){}
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }

                val autoTimeStr = String.format("%d:%02d", wpTime.autoMin / 60, wpTime.autoMin % 60)
                val valAuto = TextView(this).apply {
                    text = autoTimeStr
                    setTextColor(mutedColor)
                    textSize = 14f
                    try { typeface = ResourcesCompat.getFont(context, R.font.asap_light) } catch(e: Exception){}
                }
                subAuto.addView(lblAuto)
                subAuto.addView(valAuto)
                cardBlock.addView(subAuto)
            }

            if (wpTime.manualMin > 0) {
                val subManual = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 4, 0, 0)
                }
                val lblManual = TextView(this).apply {
                    text = "Incheckning MANUELL"
                    setTextColor(mutedColor)
                    textSize = 14f
                    try { typeface = ResourcesCompat.getFont(context, R.font.asap_light) } catch(e: Exception){}
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }

                val manualTimeStr = String.format("%d:%02d", wpTime.manualMin / 60, wpTime.manualMin % 60)
                val valManual = TextView(this).apply {
                    text = manualTimeStr
                    setTextColor(mutedColor)
                    textSize = 14f
                    try { typeface = ResourcesCompat.getFont(context, R.font.asap_light) } catch(e: Exception){}
                }
                subManual.addView(lblManual)
                subManual.addView(valManual)
                cardBlock.addView(subManual)
            }

            val divider = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                    setMargins(0, (14 * density).toInt(), 0, (12 * density).toInt())
                }
                setBackgroundColor(Color.parseColor("#15000000"))
            }
            cardBlock.addView(divider)

            val buttonRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
            }

            val btnCsv = TextView(this).apply {
                text = "EXPORTERA CSV"
                setTextColor(whiteTextColor)
                textSize = 12f
                try { typeface = ResourcesCompat.getFont(context, R.font.asap_black) } catch(e: Exception){}

                gravity = Gravity.CENTER
                includeFontPadding = false

                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = (6 * density)
                    setColor(buttonBgColor)
                }

                setPadding((16 * density).toInt(), (10 * density).toInt(), (16 * density).toInt(), (10 * density).toInt())

                setOnClickListener {
                    exportCsvForWorkplace(workplace, sourceLogs, roundInterval, savedWorkplaces, startTimeMs, isMonthly)
                }
            }
            buttonRow.addView(btnCsv)
            cardBlock.addView(buttonRow)

            container.addView(cardBlock)
        }
    }

    // =========================================================
    // EXPORT-MOTOR FÖR CSV (Körs asynkront via Coroutines)
    // =========================================================
    private fun exportCsvForWorkplace(
        workplaceName: String,
        logs: List<WorkLog>,
        roundInterval: Int,
        savedWorkplaces: String,
        startTimeMs: Long,
        isMonthly: Boolean
    ) {
        lifecycleScope.launch {
            try {
                val exportResult = withContext(Dispatchers.IO) {
                    val dailyHours = HashMap<String, Double>()
                    val sortedLogs = logs.sortedBy { it.timestamp }
                    var currentInLog: WorkLog? = null

                    for (log in sortedLogs) {
                        if (log.type == "IN") {
                            currentInLog = log
                        } else if (log.type.startsWith("UT") && currentInLog != null) {
                            val rawSsid = currentInLog.ssid ?: ""
                            val (wpName, _) = getProperWorkplaceName(rawSsid, savedWorkplaces)

                            if (wpName.equals(workplaceName, ignoreCase = true)) {
                                // --- FIX 2: Använder nu den centraliserade avrundningsfunktionen från Repositoryt ---
                                val roundedIn = repository.roundTimestamp(currentInLog.timestamp, roundInterval)
                                val roundedOut = repository.roundTimestamp(log.timestamp, roundInterval)

                                val durationMs = roundedOut - roundedIn
                                val durationHours = durationMs / 3600000.0

                                val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(roundedIn))
                                dailyHours[dateStr] = (dailyHours[dateStr] ?: 0.0) + durationHours
                            }
                            currentInLog = null
                        }
                    }

                    val csv = java.lang.StringBuilder()
                    val monthFormat = java.text.SimpleDateFormat("MMMM", Locale("sv", "SE"))
                    val yearFormat = java.text.SimpleDateFormat("yyyy", Locale("sv", "SE"))
                    val monthStr = monthFormat.format(startTimeMs).replaceFirstChar { it.uppercase() }
                    val yearStr = yearFormat.format(startTimeMs)

                    csv.append(";;;;;;;Tidrapport;;;;\n")
                    csv.append(";;;;;;;ÅR;MÅNAD;;;\n")
                    csv.append(";$workplaceName;;;;;;$yearStr;$monthStr;;;\n")
                    csv.append(";\"Tid kan anges med decimal. NOTERA att det är kommatecken innan decimal i Excel.\";;;;;;;;;;\n")
                    csv.append("Datum;Veckodag;Arbetade timmar;Varav övertid;Komptid;Sjuk;Semester/ATK;VAB/för.ledig\n")

                    val cal = Calendar.getInstance()
                    cal.timeInMillis = startTimeMs
                    val daysToLoop = if (isMonthly) cal.getActualMaximum(Calendar.DAY_OF_MONTH) else 7

                    val calEnd = Calendar.getInstance().apply {
                        timeInMillis = startTimeMs
                        add(Calendar.DAY_OF_YEAR, daysToLoop)
                    }
                    val calendarEvents = getCalendarEventsForPeriod(startTimeMs, calEnd.timeInMillis)

                    val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                    val dayFormat = java.text.SimpleDateFormat("EEEE", Locale("sv", "SE"))

                    val sjukDates = mutableListOf<String>()
                    val vabDates = mutableListOf<String>()
                    val semesterDates = mutableListOf<String>()

                    for (i in 0 until daysToLoop) {
                        val currentDate = cal.time
                        val dateStr = dateFormat.format(currentDate)
                        val dayStr = dayFormat.format(currentDate)

                        val hours = dailyHours[dateStr] ?: 0.0
                        val hoursStr = if (hours > 0) String.format(Locale("sv", "SE"), "%.2f", hours) else ""

                        val dayEvents = calendarEvents[dateStr] ?: emptyList()
                        var sjukStr = ""
                        var semesterStr = ""
                        var vabStr = ""

                        for (event in dayEvents) {
                            if (event.contains("sjuk")) {
                                sjukStr = "8,00"
                                sjukDates.add(dateStr)
                            }
                            if (event.contains("vab")) {
                                vabStr = "8,00"
                                vabDates.add(dateStr)
                            }
                            if (event.contains("semester") || event.contains("atk")) {
                                semesterStr = "8,00"
                                semesterDates.add(dateStr)
                            }
                        }

                        csv.append("$dateStr;$dayStr;$hoursStr;;;$sjukStr;$semesterStr;$vabStr\n")
                        cal.add(Calendar.DAY_OF_YEAR, 1)
                    }

                    val safeName = workplaceName.replace(Regex("[^a-zA-Z0-9åäöÅÄÖ]"), "_")
                    val fileName = "Tidrapport_${safeName}_$monthStr.csv"

                    val file = File(filesDir, fileName)
                    file.writeText(csv.toString(), Charsets.ISO_8859_1)

                    val uri = FileProvider.getUriForFile(this@ReportActivity, "${packageName}.fileprovider", file)

                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/csv"
                        putExtra(Intent.EXTRA_SUBJECT, "Tidrapport $workplaceName - $monthStr")
                        putExtra(Intent.EXTRA_TEXT, "Bifogat finns tidrapporten för $monthStr.")
                        putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = android.content.ClipData.newRawUri("", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }

                    val chooser = Intent.createChooser(intent, "Dela tidrapport via...")

                    val infoList = mutableListOf<String>()
                    sjukDates.forEach { date -> infoList.add("$date - 8 h Sjukdom") }
                    vabDates.forEach { date -> infoList.add("$date - 8 h VAB") }
                    semesterDates.forEach { date -> infoList.add("$date - 8 h Semester/ATK") }

                    ExportDataPacket(chooser, infoList)
                }

                if (exportResult.infoList.isNotEmpty()) {
                    val meddelande = "Kalenderintegrationen hittade och lade till:\n\n" + exportResult.infoList.joinToString("\n")

                    com.google.android.material.dialog.MaterialAlertDialogBuilder(this@ReportActivity, R.style.JobbzonDialogTheme)
                        .setTitle("😎")
                        .setMessage(meddelande)
                        .setPositiveButton("OK") { dialog, _ ->
                            dialog.dismiss()
                            startActivity(exportResult.chooser)
                        }
                        .show()
                } else {
                    startActivity(exportResult.chooser)
                }

            } catch (e: Exception) {
                android.util.Log.e("EXPORT_FEL", "Här är felet som stoppar exporten!", e)
                Toast.makeText(this@ReportActivity, "Något gick fel vid export: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private data class ExportDataPacket(val chooser: Intent, val infoList: List<String>)

    private fun getCalendarEventsForPeriod(startTimeMs: Long, endTimeMs: Long): Map<String, List<String>> {
        val eventsMap = HashMap<String, MutableList<String>>()

        val prefs = getSharedPreferences("arbetslogg_prefs", Context.MODE_PRIVATE)
        val isCalendarEnabled = prefs.getBoolean("use_calendar_integration", false)
        if (!isCalendarEnabled) return eventsMap

        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_CALENDAR)
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

            val cursor = contentResolver.query(builder.build(), projection, null, null, null)

            cursor?.use {
                val titleIdx = it.getColumnIndex(android.provider.CalendarContract.Instances.TITLE)
                val beginIdx = it.getColumnIndex(android.provider.CalendarContract.Instances.BEGIN)
                val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

                while (it.moveToNext()) {
                    val title = it.getString(titleIdx) ?: ""
                    val beginMs = it.getLong(beginIdx)
                    val dateStr = dateFormat.format(Date(beginMs))

                    if (!eventsMap.containsKey(dateStr)) {
                        eventsMap[dateStr] = ArrayList()
                    }
                    eventsMap[dateStr]?.add(title.lowercase(Locale.getDefault()).trim())
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("CALENDAR_READ", "Kunde inte läsa kalender: ${e.message}")
        }

        return eventsMap
    }

    private fun formatMinutes(totalMin: Long): String {
        val hours = totalMin / 60
        val minutes = totalMin % 60
        return "${hours}h ${minutes}min"
    }

    // --- LOKAL roundTimestamp METOD RENSAD HÄRIFRÅN ---
}