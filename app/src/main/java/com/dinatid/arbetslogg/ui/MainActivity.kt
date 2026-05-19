package com.dinatid.arbetslogg.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import com.dinatid.arbetslogg.R
import com.dinatid.arbetslogg.WorkLog
import com.dinatid.arbetslogg.data.TimeRepository
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()

    private var selectedDateOffset = 0
    private var wifiCountdownSeconds = -1

    private val countdownReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val secondsLeft = intent?.getIntExtra("seconds_left", -1) ?: -1
            wifiCountdownSeconds = secondsLeft

            if (secondsLeft == -1) {
                lifecycleScope.launch {
                    kotlinx.coroutines.delay(300)
                    viewModel.refreshData(selectedDateOffset, wifiCountdownSeconds)
                }
            } else {
                viewModel.refreshData(selectedDateOffset, wifiCountdownSeconds)
            }
        }
    }

    private val lunchQuestionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val workplace = intent?.getStringExtra("workplace") ?: return
            val outTime = intent.getLongExtra("out_time", 0L)
            val inTime = intent.getLongExtra("in_time", 0L)
            showLunchDialog(workplace, outTime, inTime)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        checkIntentForLunchDialog(intent)

// --- STARTA BARA TJÄNSTEN OM APPINSTÄLLNINGEN ÄR GODKÄND ---
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            val serviceIntent = Intent(this, com.dinatid.arbetslogg.service.WiFiService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        }

        // --- HÄMTA VYER ---
        val btnPrevDay = findViewById<ImageView>(R.id.btnPrevDay)
        val btnNextDay = findViewById<ImageView>(R.id.btnNextDay)
        val txtCurrentDate = findViewById<TextView>(R.id.txtCurrentDate)

        val txtCenterTime = findViewById<TextView>(R.id.txtCenterTime)
        val txtCurrentSsid = findViewById<TextView>(R.id.txtCurrentSsid)
        val txtCurrentWorkplace = findViewById<TextView>(R.id.txtCurrentWorkplace) // NY VY

        val txtInTimeCircle = findViewById<TextView>(R.id.txtInTimeCircle)
        val txtCountdownLabel = findViewById<TextView>(R.id.txtCountdownLabel)

        val imgSpinningSnake = findViewById<ImageView>(R.id.imgSpinningSnake)

        val txtStatusMessage = findViewById<TextView>(R.id.txtStatusMessage)
        val switchManualToggle = findViewById<SwitchCompat>(R.id.switchManualToggle)
        val txtToggleStatus = findViewById<TextView>(R.id.txtToggleStatus)

        val lblMonthTitle = findViewById<TextView>(R.id.lblMonthTitle)
        val txtMonthBalance = findViewById<TextView>(R.id.txtMonthBalance)
        val progressActualMonth = findViewById<ProgressBar>(R.id.progressActualMonth)
        val goalLeftSpace = findViewById<View>(R.id.goalLeftSpace)
        val goalRightSpace = findViewById<View>(R.id.goalRightSpace)

        val btnNavDashboard = findViewById<LinearLayout>(R.id.btnNavDashboard)
        val btnNavHistory = findViewById<LinearLayout>(R.id.btnNavHistory)
        val btnNavReports = findViewById<LinearLayout>(R.id.btnNavReports)
        val btnNavSettings = findViewById<LinearLayout>(R.id.btnNavSettings)

        val snakeDrawable = FadeTailDrawable()
        imgSpinningSnake.setImageDrawable(snakeDrawable)
        ObjectAnimator.ofFloat(imgSpinningSnake, "rotation", 0f, 360f).apply {
            duration = 60000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }

        btnPrevDay.setOnClickListener {
            selectedDateOffset--
            viewModel.refreshData(selectedDateOffset, wifiCountdownSeconds)
        }

        btnNextDay.setOnClickListener {
            if (selectedDateOffset < 0) {
                selectedDateOffset++
                viewModel.refreshData(selectedDateOffset, wifiCountdownSeconds)
            }
        }

        switchManualToggle.setOnClickListener {
            val isCheckingIn = switchManualToggle.isChecked
            val title = if (isCheckingIn) "Checka in?" else "Checka ut?"
            val message = if (isCheckingIn) "Vill du stämpla in manuellt?" else "Vill du stämpla ut manuellt?"

            com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.JobbzonDialogTheme)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("JA") { dialog, _ ->
                    viewModel.handleManualToggle()
                    viewModel.refreshData(selectedDateOffset, wifiCountdownSeconds)
                    dialog.dismiss()
                }
                .setNegativeButton("NEJ") { dialog, _ ->
                    switchManualToggle.isChecked = !isCheckingIn
                    dialog.dismiss()
                }
                .setOnCancelListener { switchManualToggle.isChecked = !isCheckingIn }
                .show()
        }

        btnNavDashboard.setOnClickListener { }
        btnNavHistory.setOnClickListener { startActivity(Intent(this, HistoryActivity::class.java)) }
        btnNavReports.setOnClickListener { startActivity(Intent(this, ReportActivity::class.java)) }
        btnNavSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }

        lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                val themeColor = Color.parseColor("#FCDEBB")
                val inactiveColor = Color.parseColor("#80FCDEBB")

                txtCurrentDate.text = state.currentDate
                txtCurrentDate.setTextColor(themeColor)
                btnPrevDay.imageTintList = ColorStateList.valueOf(themeColor)
                btnNextDay.imageTintList = ColorStateList.valueOf(themeColor)
                btnNextDay.isClickable = state.isNextDayClickable
                btnNextDay.alpha = state.nextDayAlpha

                txtCenterTime.text = state.centerTime
                txtCenterTime.setTextColor(themeColor)

                txtCountdownLabel.visibility = if (state.isCountdownVisible) View.VISIBLE else View.GONE
                txtCountdownLabel.setTextColor(Color.parseColor("#F44336"))

                // --- TEXTUPPDATERING ---
                txtCurrentSsid.text = state.currentSsid
                txtCurrentSsid.setTextColor(themeColor)

                if (state.currentWorkplace.isNotEmpty()) {
                    txtCurrentWorkplace.text = state.currentWorkplace
                    txtCurrentWorkplace.setTextColor(themeColor)
                    txtCurrentWorkplace.visibility = View.VISIBLE
                } else {
                    txtCurrentWorkplace.visibility = View.GONE
                }

                txtInTimeCircle.text = state.inTimeCircle
                txtInTimeCircle.setTextColor(themeColor)
                txtInTimeCircle.visibility = View.VISIBLE

                txtStatusMessage.text = state.statusMessage
                txtStatusMessage.setTextColor(themeColor)

                txtToggleStatus.text = state.toggleStatusText
                txtToggleStatus.setTextColor(themeColor)
                switchManualToggle.isChecked = state.isCurrentlyIn

                txtMonthBalance.text = state.monthBalance
                txtMonthBalance.setTextColor(themeColor)

                progressActualMonth.progress = state.monthProgress
                progressActualMonth.progressTintList = ColorStateList.valueOf(state.monthProgressColor)

                val leftParams = goalLeftSpace.layoutParams as LinearLayout.LayoutParams
                leftParams.weight = state.goalLeftWeight
                goalLeftSpace.layoutParams = leftParams

                val rightParams = goalRightSpace.layoutParams as LinearLayout.LayoutParams
                rightParams.weight = state.goalRightWeight
                goalRightSpace.layoutParams = rightParams

                val prefs = getSharedPreferences("arbetslogg_prefs", Context.MODE_PRIVATE)
                val smartMode = prefs.getInt("smart_help_mode", 1)

                if (smartMode == 2) {
                    lblMonthTitle.text = "MÅNADSMÅL (KONSULT)"
                } else {
                    lblMonthTitle.text = "DENNA MÅNADEN"
                }

                val navDashLayout = findViewById<LinearLayout>(R.id.btnNavDashboard)
                (navDashLayout.getChildAt(0) as? ImageView)?.imageTintList = ColorStateList.valueOf(themeColor)
                (navDashLayout.getChildAt(1) as? TextView)?.setTextColor(themeColor)

                val navHistLayout = findViewById<LinearLayout>(R.id.btnNavHistory)
                (navHistLayout.getChildAt(0) as? ImageView)?.imageTintList = ColorStateList.valueOf(inactiveColor)
                (navHistLayout.getChildAt(1) as? TextView)?.setTextColor(inactiveColor)

                val navRepLayout = findViewById<LinearLayout>(R.id.btnNavReports)
                (navRepLayout.getChildAt(0) as? ImageView)?.imageTintList = ColorStateList.valueOf(inactiveColor)
                (navRepLayout.getChildAt(1) as? TextView)?.setTextColor(inactiveColor)

                val navSetLayout = findViewById<LinearLayout>(R.id.btnNavSettings)
                (navSetLayout.getChildAt(0) as? ImageView)?.imageTintList = ColorStateList.valueOf(inactiveColor)
                (navSetLayout.getChildAt(1) as? TextView)?.setTextColor(inactiveColor)
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        checkIntentForLunchDialog(intent)
        checkIntentForOvertimeDialog(intent)
    }

    private fun checkIntentForLunchDialog(intent: Intent?) {
        if (intent?.getBooleanExtra("show_lunch_dialog", false) == true) {
            val workplace = intent.getStringExtra("workplace") ?: "Jobbet"
            val outTime = intent.getLongExtra("out_time", 0L)
            val inTime = intent.getLongExtra("in_time", 0L)
            showLunchDialog(workplace, outTime, inTime)
        }
    }

    private fun showLunchDialog(workplace: String, outTime: Long, inTime: Long) {
        val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
        val outStr = formatter.format(Date(outTime))
        val inStr = formatter.format(Date(inTime))

        // Räkna ut hur många minuter man var borta
        val durationMin = ((inTime - outTime) / 60000).toInt()

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.JobbzonDialogTheme)
            .setTitle("Välkommen tillbaka! 🍔")
            .setMessage("Du var borta från Wi-Fi i $durationMin minuter ($outStr - $inStr).\n\nVar du på lunch?")
            .setCancelable(false) // Tvingar användaren att svara
            .setPositiveButton("JA (LUNCH)") { dialog, _ ->
                lifecycleScope.launch {
                    val repo = TimeRepository(applicationContext)

                    // --- SCENARIO 1: JA, DET VAR LUNCH! ---
                    // Vi låter "UT"-loggen från när man gick ligga kvar, för då gick man på lunch.
                    // Men vi måste checka IN personen igen nu när de är tillbaka!

                    repo.insertLog(WorkLog(type = "IN", timestamp = inTime, ssid = workplace))

                    viewModel.refreshData(selectedDateOffset, wifiCountdownSeconds)
                }
                dialog.dismiss()
            }
            .setNegativeButton("NEJ (JOBBADE)") { dialog, _ ->
                lifecycleScope.launch {
                    val repo = TimeRepository(applicationContext)
                    val lastLog = repo.getLastLog()

                    // --- SCENARIO 2: NEJ, MAN JOBBADE ---
                    // Aj då, Wi-Fi bröts men man jobbade ändå (t.ex. möte eller källaren).
                    // Vi raderar den felaktiga UT-loggen som skapades när man gick.
                    // (Observera att vi bara raderar den om det faktiskt ÄR en UT-logg, som en säkerhetsåtgärd).

                    if (lastLog != null && lastLog.type.startsWith("UT")) {
                        repo.deleteLog(lastLog)
                    }

                    // Nu rullar tiden bara vidare, utan avbrott!

                    viewModel.refreshData(selectedDateOffset, wifiCountdownSeconds)
                }
                dialog.dismiss()
            }
            .show()
    }
    private fun checkIntentForOvertimeDialog(intent: Intent?) {
        if (intent?.getBooleanExtra("show_overtime_dialog", false) == true) {
            val workplace = intent.getStringExtra("workplace") ?: "Jobbet"
            val inTime = intent.getLongExtra("in_time", 0L)
            showOvertimeDialog(workplace, inTime)
        }
    }

    private fun showOvertimeDialog(workplace: String, inTime: Long) {
        val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
        val inStr = formatter.format(Date(inTime))

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.JobbzonDialogTheme)
            .setTitle("Långt pass! ⏰")
            .setMessage("Du har varit inloggad på $workplace sedan kl $inStr.\n\nJobbar du fortfarande eller glömde du att stämpla ut?")
            .setCancelable(false)
            .setPositiveButton("JOBBAR ÄNNU") { dialog, _ ->
                // Gör ingenting, låt klockan fortsätta ticka
                dialog.dismiss()
            }
            .setNegativeButton("CHECK UT EFTER 8h") { dialog, _ ->
                lifecycleScope.launch {
                    val repo = TimeRepository(applicationContext)
                    val outTime = inTime + (8 * 60 * 60 * 1000L) // Loggar ut exakt 8 timmar efter start
                    repo.insertLog(WorkLog(type = "UT (Auto)", timestamp = outTime, ssid = workplace))
                    repo.setManualOverride(false)
                    viewModel.refreshData(selectedDateOffset, wifiCountdownSeconds)
                }
                dialog.dismiss()
            }
            .setNeutralButton("CHECK UT NU") { dialog, _ ->
                lifecycleScope.launch {
                    val repo = TimeRepository(applicationContext)
                    repo.insertLog(WorkLog(type = "UT (Manuell)", timestamp = System.currentTimeMillis(), ssid = workplace))
                    repo.setManualOverride(false)
                    viewModel.refreshData(selectedDateOffset, wifiCountdownSeconds)
                }
                dialog.dismiss()
            }
            .show()
    }
    override fun onStart() {
        super.onStart()
        val broadcastManager = androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this)
        broadcastManager.registerReceiver(countdownReceiver, IntentFilter("COUNTDOWN_UPDATE"))
        broadcastManager.registerReceiver(lunchQuestionReceiver, IntentFilter("LUNCH_QUESTION_EVENT"))

        viewModel.refreshData(selectedDateOffset, wifiCountdownSeconds)
    }

    override fun onStop() {
        super.onStop()
        val broadcastManager = androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this)
        broadcastManager.unregisterReceiver(countdownReceiver)
        broadcastManager.unregisterReceiver(lunchQuestionReceiver)
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshData(selectedDateOffset, wifiCountdownSeconds)
    }
}