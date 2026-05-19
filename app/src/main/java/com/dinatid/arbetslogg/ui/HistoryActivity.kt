package com.dinatid.arbetslogg.ui

import android.app.TimePickerDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dinatid.arbetslogg.R
import com.dinatid.arbetslogg.WorkLog
import com.dinatid.arbetslogg.data.TimeRepository
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class HistoryActivity : AppCompatActivity() {

    private lateinit var repository: TimeRepository
    private lateinit var logAdapter: SmartHistoryAdapter
    private lateinit var rvHistoryLogs: RecyclerView

    // Håller koll på vilken textruta vi pratar in text i
    private var currentActiveEditText: android.widget.EditText? = null

    // --- DET NYA, MODERNA SÄTTET ATT HANTERA RÖSTINMATNING (Ersätter onActivityResult) ---
    private val speechRecognizerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val results = result.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
            if (!results.isNullOrEmpty()) {
                val spokenText = results[0]

                currentActiveEditText?.let { editText ->
                    val currentText = editText.text.toString()
                    if (currentText.isBlank()) {
                        editText.setText(spokenText)
                    } else {
                        editText.setText("$currentText $spokenText")
                    }
                    editText.setSelection(editText.text.length)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        repository = TimeRepository(this)

        rvHistoryLogs = findViewById(R.id.rvHistoryLogs)
        rvHistoryLogs.layoutManager = LinearLayoutManager(this)

        logAdapter = SmartHistoryAdapter(emptyList()) { selectedShift ->
            showEditShiftDialog(selectedShift)
        }
        rvHistoryLogs.adapter = logAdapter

        // --- SWIPE TO DELETE LOGIK ---
        val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(r: RecyclerView, v: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder) = false

            override fun getSwipeDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
                if (viewHolder is SmartHistoryAdapter.HeaderViewHolder) return 0
                return super.getSwipeDirs(recyclerView, viewHolder)
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.adapterPosition
                val item = logAdapter.getItem(position) as? HistoryUiItem.Shift

                if (item != null) {
                    MaterialAlertDialogBuilder(this@HistoryActivity, R.style.JobbzonDialogTheme)
                        .setTitle("Radera pass?")
                        .setMessage("Vill du ta bort passet på ${item.workplace} (${item.timeRange}) från historiken?")
                        .setPositiveButton("JA, RADERA") { _, _ ->
                            deleteShift(item)
                        }
                        .setNegativeButton("AVBRYT") { _, _ ->
                            logAdapter.notifyItemChanged(position)
                        }
                        .setOnCancelListener {
                            logAdapter.notifyItemChanged(position)
                        }
                        .show()
                }
            }
        })
        itemTouchHelper.attachToRecyclerView(rvHistoryLogs)

        loadHistoryData()

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
        btnNavReports.setOnClickListener {
            startActivity(Intent(this, ReportActivity::class.java))
            overridePendingTransition(0, 0)
            finish()
        }
        btnNavSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
            overridePendingTransition(0, 0)
            finish()
        }

        val themeColor = Color.parseColor("#FCDEBB")
        val inactiveColor = Color.parseColor("#80FCDEBB")

        (btnNavDashboard.getChildAt(0) as? ImageView)?.imageTintList = ColorStateList.valueOf(inactiveColor)
        (btnNavDashboard.getChildAt(1) as? TextView)?.setTextColor(inactiveColor)
        (btnNavReports.getChildAt(0) as? ImageView)?.imageTintList = ColorStateList.valueOf(inactiveColor)
        (btnNavReports.getChildAt(1) as? TextView)?.setTextColor(inactiveColor)
        (btnNavSettings.getChildAt(0) as? ImageView)?.imageTintList = ColorStateList.valueOf(inactiveColor)
        (btnNavSettings.getChildAt(1) as? TextView)?.setTextColor(inactiveColor)

        (btnNavHistory.getChildAt(0) as? ImageView)?.imageTintList = ColorStateList.valueOf(themeColor)
        (btnNavHistory.getChildAt(1) as? TextView)?.setTextColor(themeColor)
    }

    private fun loadHistoryData() {
        lifecycleScope.launch {
            val rawLogs = repository.getAllLogs()
            val userRoundingInterval = repository.getRoundingInterval()

            // --- FIX: FLyttar den tunga beräkningen till en bakgrundstråd (Dispatchers.Default) ---
            val historyItems = withContext(Dispatchers.Default) {
                transformLogsToHistoryItems(rawLogs, userRoundingInterval)
            }

            logAdapter.updateData(historyItems)
        }
    }

    private fun deleteShift(shift: HistoryUiItem.Shift) {
        lifecycleScope.launch {
            repository.deleteLog(shift.inLog)
            if (shift.outLog != null) {
                repository.deleteLog(shift.outLog)
            }
            Toast.makeText(this@HistoryActivity, "Passet raderades!", Toast.LENGTH_SHORT).show()
            loadHistoryData()
        }
    }

    // =========================================================
    // DIALOGRUTAN FÖR ATT REDIGERA TIDER & KOMMENTARER
    // =========================================================
    private fun showEditShiftDialog(shift: HistoryUiItem.Shift) {
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

        val calIn = Calendar.getInstance().apply { timeInMillis = shift.inLog.timestamp }
        val calOut = Calendar.getInstance().apply {
            timeInMillis = shift.outLog?.timestamp ?: System.currentTimeMillis()
        }

        var newInHour = calIn.get(Calendar.HOUR_OF_DAY)
        var newInMinute = calIn.get(Calendar.MINUTE)
        var newOutHour = calOut.get(Calendar.HOUR_OF_DAY)
        var newOutMinute = calOut.get(Calendar.MINUTE)

        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (24 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
        }

        val txtInLabel = TextView(this).apply {
            text = "STARTTID: ${timeFormat.format(calIn.time)} (Klicka för att ändra)"
            textSize = 16f
            setTextColor(Color.parseColor("#424242"))
            setPadding(0, 0, 0, 24)
            setOnClickListener {
                TimePickerDialog(this@HistoryActivity, { _, h, m ->
                    newInHour = h
                    newInMinute = m
                    calIn.set(Calendar.HOUR_OF_DAY, h)
                    calIn.set(Calendar.MINUTE, m)
                    text = "STARTTID: ${timeFormat.format(calIn.time)} (Klicka för att ändra)"
                }, newInHour, newInMinute, true).show()
            }
        }

        val txtOutLabel = TextView(this).apply {
            text = if (shift.outLog != null) {
                "SLUTTID: ${timeFormat.format(calOut.time)} (Klicka för att ändra)"
            } else {
                "SLUTTID: Passet pågår fortfarande"
            }
            textSize = 16f
            setTextColor(Color.parseColor("#424242"))
            setPadding(0, 0, 0, 32)

            if (shift.outLog != null) {
                setOnClickListener {
                    TimePickerDialog(this@HistoryActivity, { _, h, m ->
                        newOutHour = h
                        newOutMinute = m
                        calOut.set(Calendar.HOUR_OF_DAY, h)
                        calOut.set(Calendar.MINUTE, m)
                        text = "SLUTTID: ${timeFormat.format(calOut.time)} (Klicka för att ändra)"
                    }, newOutHour, newOutMinute, true).show()
                }
            }
        }

        val commentRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        val editComment = android.widget.EditText(this).apply {
            hint = "Lägg till en kommentar (frivilligt)..."
            setText(shift.inLog.comment ?: "")
            setTextColor(Color.parseColor("#424242"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnMic = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            contentDescription = "Tala in kommentar"
            val size = (40 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                setMargins((8 * resources.displayMetrics.density).toInt(), 0, 0, 0)
            }

            setOnClickListener {
                if (androidx.core.content.ContextCompat.checkSelfPermission(
                        this@HistoryActivity,
                        android.Manifest.permission.RECORD_AUDIO
                    ) != android.content.pm.PackageManager.PERMISSION_GRANTED) {

                    androidx.core.app.ActivityCompat.requestPermissions(
                        this@HistoryActivity,
                        arrayOf(android.Manifest.permission.RECORD_AUDIO),
                        101
                    )
                } else {
                    try {
                        val speechIntent = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                            putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Tala in din kommentar...")
                        }
                        // --- FIX: Använd den nya launchern istället för startActivityForResult ---
                        currentActiveEditText = editComment
                        speechRecognizerLauncher.launch(speechIntent)
                    } catch (e: Exception) {
                        Toast.makeText(this@HistoryActivity, "Röstinmatning stöds inte på denna enhet", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        commentRow.addView(editComment)
        commentRow.addView(btnMic)

        dialogView.addView(txtInLabel)
        dialogView.addView(txtOutLabel)
        dialogView.addView(commentRow)

        MaterialAlertDialogBuilder(this, R.style.JobbzonDialogTheme)
            .setTitle("Redigera pass – ${shift.workplace}")
            .setView(dialogView)
            .setNegativeButton("AVBRYT", null)
            .setPositiveButton("SPARA") { _, _ ->
                lifecycleScope.launch {
                    val userComment = editComment.text.toString().takeIf { it.isNotBlank() }

                    val updatedInLog = shift.inLog.copy(
                        timestamp = calIn.timeInMillis,
                        comment = userComment
                    )
                    repository.insertLog(updatedInLog)

                    if (shift.outLog != null) {
                        if (calOut.timeInMillis < calIn.timeInMillis) {
                            Toast.makeText(this@HistoryActivity, "Fel: Sluttiden kan inte vara före starttiden!", Toast.LENGTH_LONG).show()
                            return@launch
                        }
                        val updatedOutLog = shift.outLog.copy(
                            timestamp = calOut.timeInMillis,
                            comment = userComment
                        )
                        repository.insertLog(updatedOutLog)
                    }

                    Toast.makeText(this@HistoryActivity, "Passet uppdaterades!", Toast.LENGTH_SHORT).show()
                    loadHistoryData()
                }
            }
            .show()
    }

    // =========================================================
    // DOKUMENTATION & BYGGER LISTAN FÖR HISTORIKEN
    // =========================================================
    private fun transformLogsToHistoryItems(logs: List<WorkLog>, roundInterval: Int): List<HistoryUiItem> {
        if (logs.isEmpty()) return emptyList()

        val sortedLogs = logs.sortedBy { it.timestamp }
        val shiftPairs = mutableListOf<Pair<Long, HistoryUiItem.Shift>>()
        val dailyTotalMinutes = HashMap<String, Long>()

        val dateFormatDay = SimpleDateFormat("EEEE d MMMM", Locale.getDefault())
        val dateFormatTime = SimpleDateFormat("HH:mm", Locale.getDefault())

        var currentInLog: WorkLog? = null

        for (log in sortedLogs) {
            if (log.type == "IN") {
                currentInLog = log
            } else if (log.type.startsWith("UT") && currentInLog != null) {
                val roundedIn = if (roundInterval > 0) roundTimestamp(currentInLog.timestamp, roundInterval) else currentInLog.timestamp
                val roundedOut = if (roundInterval > 0) roundTimestamp(log.timestamp, roundInterval) else log.timestamp

                val inTimeStr = dateFormatTime.format(Date(roundedIn))
                val outTimeStr = dateFormatTime.format(Date(roundedOut))

                val durationMs = roundedOut - roundedIn
                val durationMin = durationMs / 60000
                val hours = durationMin / 60
                val minutes = durationMin % 60
                val dayName = dateFormatDay.format(Date(roundedIn)).uppercase()

                dailyTotalMinutes[dayName] = (dailyTotalMinutes[dayName] ?: 0L) + durationMin

                val rawSsid = currentInLog.ssid ?: ""
                val workplaceName = when {
                    rawSsid.contains("-") -> rawSsid.substringAfter("-").trim()
                    rawSsid.startsWith("Manuell", ignoreCase = true) -> "Manuell Incheckning"
                    else -> rawSsid
                }

                val shift = HistoryUiItem.Shift(
                    inLog = currentInLog,
                    outLog = log,
                    day = dayName,
                    workplace = workplaceName,
                    timeRange = "$inTimeStr - $outTimeStr",
                    duration = "${hours}h ${minutes}m",
                    comment = currentInLog.comment
                )
                shiftPairs.add(Pair(roundedIn, shift))
                currentInLog = null
            }
        }

        if (currentInLog != null) {
            val roundedIn = if (roundInterval > 0) roundTimestamp(currentInLog.timestamp, roundInterval) else currentInLog.timestamp
            val roundedOut = if (roundInterval > 0) roundTimestamp(System.currentTimeMillis(), roundInterval) else System.currentTimeMillis()

            val inTimeStr = dateFormatTime.format(Date(roundedIn))
            val durationMs = roundedOut - roundedIn
            val durationMin = durationMs / 60000
            val hours = durationMin / 60
            val minutes = durationMin % 60
            val dayName = dateFormatDay.format(Date(roundedIn)).uppercase()

            dailyTotalMinutes[dayName] = (dailyTotalMinutes[dayName] ?: 0L) + durationMin

            val rawSsid = currentInLog.ssid ?: ""
            val workplaceName = when {
                rawSsid.contains("-") -> rawSsid.substringAfter("-").trim()
                rawSsid.startsWith("Manuell", ignoreCase = true) -> "Manuell Incheckning"
                else -> rawSsid
            }

            val shift = HistoryUiItem.Shift(
                inLog = currentInLog,
                outLog = null,
                day = dayName,
                workplace = workplaceName,
                timeRange = "$inTimeStr - Pågår",
                duration = "${hours}h ${minutes}m",
                comment = currentInLog.comment
            )
            shiftPairs.add(Pair(roundedIn, shift))
        }

        val sortedPairs = shiftPairs.sortedByDescending { it.first }
        val groupedByDay = LinkedHashMap<String, MutableList<HistoryUiItem.Shift>>()

        for (pair in sortedPairs) {
            val dayName = pair.second.day
            if (!groupedByDay.containsKey(dayName)) {
                groupedByDay[dayName] = mutableListOf()
            }
            groupedByDay[dayName]?.add(pair.second)
        }

        val finalItems = mutableListOf<HistoryUiItem>()
        for ((day, shiftList) in groupedByDay) {
            val totalMin = dailyTotalMinutes[day] ?: 0L
            val totalHours = totalMin / 60
            val totalRemainingMin = totalMin % 60
            val totalString = " — ${totalHours} TIM ${totalRemainingMin} MIN"

            finalItems.add(HistoryUiItem.Header("$day$totalString"))
            finalItems.addAll(shiftList)
        }

        return finalItems
    }

    private fun roundTimestamp(timestamp: Long, minutesInterval: Int): Long {
        val msInMinute = 60000L
        val intervalMs = minutesInterval * msInMinute
        return ((timestamp + intervalMs / 2) / intervalMs) * intervalMs
    }
}

// --- LOKALA KLASSER FÖR LISTAN ---

sealed class HistoryUiItem {
    data class Header(val title: String) : HistoryUiItem()
    data class Shift(
        val inLog: WorkLog,
        val outLog: WorkLog?,
        val day: String,
        val workplace: String,
        val timeRange: String,
        val duration: String,
        val comment: String?
    ) : HistoryUiItem()
}

class SmartHistoryAdapter(
    private var items: List<HistoryUiItem>,
    private val onItemClick: (HistoryUiItem.Shift) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    fun getItem(position: Int): HistoryUiItem = items[position]

    fun updateData(newItems: List<HistoryUiItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is HistoryUiItem.Header -> 0
            is HistoryUiItem.Shift -> 1
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == 0) {
            val view = inflater.inflate(R.layout.item_history_header, parent, false)
            HeaderViewHolder(view)
        } else {
            val view = inflater.inflate(R.layout.item_work_log, parent, false)
            ShiftViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        if (holder is HeaderViewHolder && item is HistoryUiItem.Header) {
            holder.txtHeader.text = item.title
        } else if (holder is ShiftViewHolder && item is HistoryUiItem.Shift) {
            holder.txtWorkplace.text = item.workplace
            holder.txtTimeRange.text = item.timeRange
            holder.txtDuration.text = item.duration

            // Logik för att visa eller dölja kommentaren i listan
            if (item.comment.isNullOrBlank()) {
                holder.txtComment.visibility = View.GONE
            } else {
                holder.txtComment.visibility = View.VISIBLE
                holder.txtComment.text = "💬 ${item.comment}"
            }

            holder.itemView.setOnClickListener { onItemClick(item) }
        }
    }

    override fun getItemCount() = items.size

    class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val txtHeader: TextView = view.findViewById(R.id.txtHistoryDateHeader)
    }

    class ShiftViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val txtWorkplace: TextView = view.findViewById(R.id.txtWorkplace)
        val txtTimeRange: TextView = view.findViewById(R.id.txtTimeRange)
        val txtDuration: TextView = view.findViewById(R.id.txtDuration)
        val txtComment: TextView = view.findViewById(R.id.txtComment)
    }
}