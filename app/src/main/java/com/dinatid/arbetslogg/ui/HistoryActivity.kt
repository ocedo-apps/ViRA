package com.dinatid.arbetslogg.ui

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.dinatid.arbetslogg.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class HistoryActivity : AppCompatActivity() {

    private val viewModel: HistoryViewModel by viewModels()

    // Verktyg för att fånga upp röstinmatning (Speech-to-Text)
    private lateinit var speechLauncher: ActivityResultLauncher<Intent>
    private var activeEditText: EditText? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        // --- REGISTRERA RÖST-LYSSNAREN ---
        speechLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                if (!matches.isNullOrEmpty()) {
                    val spokenText = matches[0]
                    val currentText = activeEditText?.text?.toString() ?: ""

                    // Lägg till ett mellanslag om det redan fanns text innan
                    val newText = if (currentText.isEmpty()) spokenText else "$currentText $spokenText"

                    activeEditText?.setText(newText)
                    activeEditText?.setSelection(newText.length) // Sätt markören i slutet
                }
            }
        }

        // --- HÄMTA VYER FÖR KALENDERN ---
        val btnBack = findViewById<ImageView>(R.id.btnBack)
        val btnPrevMonth = findViewById<ImageView>(R.id.btnPrevMonth)
        val btnNextMonth = findViewById<ImageView>(R.id.btnNextMonth)
        val txtCurrentMonth = findViewById<TextView>(R.id.txtCurrentMonth)
        val rvCalendar = findViewById<RecyclerView>(R.id.rvCalendar)

        // --- HÄMTA VYER FÖR MENYRADEN (BOTTOM NAV) ---
        val btnNavDashboard = findViewById<LinearLayout>(R.id.btnNavDashboard)
        val btnNavHistory = findViewById<LinearLayout>(R.id.btnNavHistory)
        val btnNavReports = findViewById<LinearLayout>(R.id.btnNavReports)
        val btnNavSettings = findViewById<LinearLayout>(R.id.btnNavSettings)

        // --- SÄTT FÄRGER PÅ MENYRADEN ---
        val activeColor = ContextCompat.getColor(this, R.color.sunset_creme)
        val inactiveColor = ContextCompat.getColor(this, R.color.sunset_creme_inactive)

        (btnNavDashboard.getChildAt(0) as? ImageView)?.imageTintList = ColorStateList.valueOf(inactiveColor)
        (btnNavDashboard.getChildAt(1) as? TextView)?.setTextColor(inactiveColor)

        (btnNavHistory.getChildAt(0) as? ImageView)?.imageTintList = ColorStateList.valueOf(activeColor)
        (btnNavHistory.getChildAt(1) as? TextView)?.setTextColor(activeColor)

        (btnNavReports.getChildAt(0) as? ImageView)?.imageTintList = ColorStateList.valueOf(inactiveColor)
        (btnNavReports.getChildAt(1) as? TextView)?.setTextColor(inactiveColor)

        (btnNavSettings.getChildAt(0) as? ImageView)?.imageTintList = ColorStateList.valueOf(inactiveColor)
        (btnNavSettings.getChildAt(1) as? TextView)?.setTextColor(inactiveColor)

        // --- KLICKHANTERING FÖR TOP BAR OCH KALENDER ---
        btnBack.setOnClickListener { finish() }
        btnPrevMonth.setOnClickListener { viewModel.changeMonth(-1) }
        btnNextMonth.setOnClickListener { viewModel.changeMonth(1) }

        // --- KLICKHANTERING FÖR MENYRADEN ---
        btnNavDashboard.setOnClickListener { finish() }
        btnNavReports.setOnClickListener {
            startActivity(Intent(this, ReportActivity::class.java))
            finish()
        }
        btnNavSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
            finish()
        }

        // --- UPPDATERA FLÖDEN FRÅN VIEWMODEL ---
        lifecycleScope.launch {
            viewModel.currentMonthText.collectLatest { monthText ->
                txtCurrentMonth.text = monthText
            }
        }

        lifecycleScope.launch {
            viewModel.calendarDays.collectLatest { days ->
                val adapter = CalendarAdapter(days) { clickedDay ->
                    showDayDetailsPopup(clickedDay)
                }
                rvCalendar.adapter = adapter
            }
        }
    }

    /**
     * POPUP 1: DAGSÖVERSIKT
     * Visar loggad tid, stämplingar och eventuell dagsanteckning.
     */
    private fun showDayDetailsPopup(day: CalendarDay) {
        if (day.date == null) return

        lifecycleScope.launch {
            val note = viewModel.getNoteForDay(day.date)
            val dateFormatter = SimpleDateFormat("EEEE d MMMM", Locale("sv", "SE"))
            val dateString = dateFormatter.format(day.date).uppercase()

            var message = ""

            // Kolla om det är helgdag
            if (day.isHoliday) {
                message += "🎈 ${day.holidayName}\n\n"
            }

            // Bygg tids- och stämplingsinfon
            if (day.loggedMinutes > 0) {
                message += getString(R.string.msg_logged_time_format, day.loggedMinutes / 60, day.loggedMinutes % 60)

                val timeFormatter = SimpleDateFormat("HH:mm", Locale.getDefault())
                for (log in day.dayLogs) {
                    val time = timeFormatter.format(Date(log.timestamp))
                    val type = if (log.type == com.dinatid.arbetslogg.WorkLog.TYPE_IN) "IN " else "UT "
                    val ssid = log.ssid.takeIf { it.isNotEmpty() } ?: "Okänd"
                    message += "• $type kl $time ($ssid)\n"
                }
            } else if (day.isHoliday) {
                message += getString(R.string.msg_ledig_day)
            } else {
                message += getString(R.string.msg_no_time_logged)
            }

            // Lägg till dagsanteckningen om den finns
            if (!note.isNullOrBlank()) {
                message += getString(R.string.msg_your_note, note)
            }

            // Inflata vår egna 100% skottsäkra XML-layout
            val dialogView = layoutInflater.inflate(R.layout.layout_dialog_details, null)
            dialogView.findViewById<TextView>(R.id.txtDialogTitle).text = dateString
            dialogView.findViewById<TextView>(R.id.txtDialogMessage).text = message

            val dialog = MaterialAlertDialogBuilder(this@HistoryActivity, R.style.JobbzonDialogTheme)
                .setView(dialogView)
                .setCancelable(true)
                .create()

            // Kryssknappen stänger popupen
            dialogView.findViewById<ImageView>(R.id.btnClose).setOnClickListener {
                dialog.dismiss()
            }

            // Knappen stänger dagsöversikten och skickar med hela 'day'-objektet till skrivläget
            dialogView.findViewById<Button>(R.id.btnDagsanteckning).setOnClickListener {
                dialog.dismiss()
                showCommentPopup(day, note ?: "")
            }

            dialog.show()
        }
    }

    /**
     * POPUP 2: SKRIVLÄGE (DAGSANTECKNING)
     * Innehåller textfält, mikrofonknapp för röstinmatning samt Spara-knapp.
     */
    private fun showCommentPopup(day: CalendarDay, currentNote: String) {
        val date = day.date ?: return

        // Inflata XML-layouten för skrivläget
        val dialogView = layoutInflater.inflate(R.layout.layout_dialog_comment, null)
        val inputNote = dialogView.findViewById<EditText>(R.id.inputNote)
        val micBtn = dialogView.findViewById<ImageView>(R.id.micBtn)
        val btnSpara = dialogView.findViewById<Button>(R.id.btnSpara)
        val btnClose = dialogView.findViewById<ImageView>(R.id.btnClose)

        activeEditText = inputNote
        inputNote.setText(currentNote)
        inputNote.setSelection(currentNote.length) // Sätt markören längst bak

        val dialog = MaterialAlertDialogBuilder(this, R.style.JobbzonDialogTheme)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        // Kryssknapp: Stänger skrivläget och öppnar dagsöversikten igen
        btnClose.setOnClickListener {
            dialog.dismiss()
            showDayDetailsPopup(day)
        }

        // Mikrofonknapp: Startar Androids inbyggda Tal-till-text
        // Setup röstinmatning
        micBtn.setOnClickListener {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)

                // --- DYNAMISKT SPRÅK: Följer telefonens systeminställning ---
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, Locale.getDefault())

                putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.prompt_speak))
            }
            try {
                speechLauncher.launch(intent)
            } catch (e: Exception) {
                Toast.makeText(this, getString(R.string.error_mic_not_supported), Toast.LENGTH_SHORT).show()
            }
        }

        // Spara-knapp: Sparar i databasen via ViewModel och hoppar tillbaka till dagsöversikten
        btnSpara.setOnClickListener {
            viewModel.saveNoteForDay(date, inputNote.text.toString())
            dialog.dismiss()

            // Liten fördröjning (100ms) så databasen hinner uppdateras innan popupen hämtar datan på nytt
            lifecycleScope.launch {
                kotlinx.coroutines.delay(100)
                showDayDetailsPopup(day)
            }
        }

        dialog.show()
    }
}