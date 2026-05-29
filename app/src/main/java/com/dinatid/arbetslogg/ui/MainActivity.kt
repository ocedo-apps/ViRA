package com.dinatid.arbetslogg.ui

import android.annotation.SuppressLint
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
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.work.*
import com.dinatid.arbetslogg.R
import com.dinatid.arbetslogg.WorkLog
import com.dinatid.arbetslogg.data.TimeRepository
import com.dinatid.arbetslogg.worker.MonthlyExportWorker
import com.dinatid.arbetslogg.worker.WiFiBackupWorker
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()
    private lateinit var viewPager: androidx.viewpager2.widget.ViewPager2

    override fun onCreate(savedInstanceState: Bundle?) {
        val repo = TimeRepository.getInstance(applicationContext)
        if (repo.getAppTheme() == 1) {
            setTheme(R.style.Theme_ViRA_Modern)
        } else {
            setTheme(R.style.Theme_ViRA)
        }

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        checkIntentForGapDialog(intent)
        checkIntentForOvertimeDialog(intent)

        // --- STARTA BARA TJÄNSTEN OM APPINSTÄLLNINGEN ÄR GODKÄND ---
        if (ContextCompat.checkSelfPermission(
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
            
            // NYTT: Tvinga en direkt Wi-Fi-koll så att statusen är rätt direkt när appen öppnas
            val forceIntent = Intent(this, com.dinatid.arbetslogg.service.WiFiService::class.java).apply {
                action = "FORCE_CHECK"
            }
            startService(forceIntent)
        }

        // --- HÄMTA BOTENMENYNS LAYOUTER ---
        val btnNavDashboard = findViewById<LinearLayout>(R.id.btnNavDashboard)
        val btnNavHistory = findViewById<LinearLayout>(R.id.btnNavHistory)
        val btnNavReports = findViewById<LinearLayout>(R.id.btnNavReports)
        val btnNavSettings = findViewById<LinearLayout>(R.id.btnNavSettings)

        // --- KONFIGURERA VIEWPIAGER2 (KARUSELLEN) ---
        viewPager = findViewById(R.id.mainViewPager)
        val pagerAdapter = MainPagerAdapter(this)
        viewPager.adapter = pagerAdapter

        // Gör så att man inte kan swipa av misstag om man är mitt uppe i att skrolla i en lista
        viewPager.offscreenPageLimit = 3

        // --- KLICK-LYSSNARE FÖR BOTTENMENYN ---
        btnNavDashboard.setOnClickListener { viewPager.setCurrentItem(0, false) }
        btnNavHistory.setOnClickListener { viewPager.setCurrentItem(1, false) }
        btnNavReports.setOnClickListener { viewPager.setCurrentItem(2, false) }
        btnNavSettings.setOnClickListener { viewPager.setCurrentItem(3, false) }

        // --- LYSSNA PÅ SWIPE-RÖRELSER OCH UPPDATERA FÄRGER I MENYN ---
        viewPager.registerOnPageChangeCallback(object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                updateBottomNavColors(position)
            }
        })

        // --- KOLLA OM ARBETSPLATS/WIFI BEHÖVER STÄLLAS IN ---
        checkWorkplaceSetup()

        // --- SCHEMALÄGG PERIODISK BAKGRUNDSKONTROLL (WorkManager) ---
        setupBackupWorker()
        setupExportWorker()
    }

    @SuppressLint("NewApi")
    private fun setupExportWorker() {
        val exportRequest = PeriodicWorkRequestBuilder<MonthlyExportWorker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build())
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "MonthlyExportWork",
            ExistingPeriodicWorkPolicy.KEEP,
            exportRequest
        )
    }

    @SuppressLint("NewApi")
    private fun setupBackupWorker() {
        val backupRequest = PeriodicWorkRequestBuilder<WiFiBackupWorker>(1, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build())
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "WiFiBackupWork",
            ExistingPeriodicWorkPolicy.KEEP,
            backupRequest
        )
    }

    private fun checkWorkplaceSetup() {
        val repo = TimeRepository.getInstance(applicationContext)
        val ssid = repo.getTargetSsid()
        val hasDeclined = repo.hasDeclinedWorkplaceSetup()

        if (ssid.isEmpty() && !hasDeclined) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.setup_workplace_title))
                .setMessage(getString(R.string.setup_workplace_msg))
                .setCancelable(false)
                .setPositiveButton(getString(R.string.setup_workplace_yes)) { dialog, _ ->
                    // Skicka användaren till inställningsfliken
                    viewPager.setCurrentItem(3, true)
                    dialog.dismiss()
                }
                .setNegativeButton(getString(R.string.setup_workplace_no)) { dialog, _ ->
                    repo.setDeclinedWorkplaceSetup(true)
                    dialog.dismiss()

                    // Visa "Okej, kan ställas in senare"-popup
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                        .setTitle(getString(R.string.setup_workplace_later_title))
                        .setMessage(getString(R.string.setup_workplace_later_msg))
                        .setPositiveButton("OK") { d, _ -> d.dismiss() }
                        .show()
                }
                .show()
        }
    }

    // --- FUNKTION SOM ÄNDRAR FÄRG PÅ IKONERNA BASERAT PÅ VALD FLIK ---
    @SuppressLint("NewApi")
    private fun updateBottomNavColors(selectedPosition: Int) {
        val themeColor = Color.parseColor("#FCDEBB") // Ljusorange / Guld
        val inactiveColor = Color.parseColor("#80FCDEBB") // Genomskinlig matt version

        val navLayouts = listOf(
            findViewById<LinearLayout>(R.id.btnNavDashboard),
            findViewById<LinearLayout>(R.id.btnNavHistory),
            findViewById<LinearLayout>(R.id.btnNavReports),
            findViewById<LinearLayout>(R.id.btnNavSettings)
        )

        for (i in navLayouts.indices) {
            val layout = navLayouts[i] ?: continue
            val color = if (i == selectedPosition) themeColor else inactiveColor

            (layout.getChildAt(0) as? ImageView)?.imageTintList = ColorStateList.valueOf(color)
            (layout.getChildAt(1) as? TextView)?.setTextColor(color)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        checkIntentForGapDialog(intent)
        checkIntentForOvertimeDialog(intent)
        checkIntentForTabRedirection(intent)
    }

    private fun checkIntentForTabRedirection(intent: Intent?) {
        val targetTab = intent?.getIntExtra("open_tab", -1) ?: -1
        if (targetTab != -1) {
            viewPager.setCurrentItem(targetTab, true)
        }
    }

    private fun checkIntentForGapDialog(intent: Intent?) {
        if (intent?.getBooleanExtra("show_gap_dialog", false) == true || 
            intent?.getBooleanExtra("show_lunch_dialog", false) == true) {
            val workplace = intent.getStringExtra("workplace") ?: "Jobbet"
            val outTime = intent.getLongExtra("out_time", 0L)
            val inTime = intent.getLongExtra("in_time", 0L)
            showGapDialog(workplace, outTime, inTime)
        }
    }

    private fun showGapDialog(workplace: String, outTime: Long, inTime: Long) {
        val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
        val outStr = formatter.format(Date(outTime))
        val inStr = formatter.format(Date(inTime))
        val durationMin = ((inTime - outTime) / 60000).toInt()

        val outCal = Calendar.getInstance().apply { timeInMillis = outTime }
        val timeOfDay = outCal.get(Calendar.HOUR_OF_DAY) + (outCal.get(Calendar.MINUTE) / 60.0)
        val isLunchTime = timeOfDay in 10.5..13.5

        val options = if (isLunchTime) {
            arrayOf("Lunchrast 🍔", "Privat ärende 🚶", "Tandläkare / Läkare 🏥", "Annat ⚙️", "Nej, jag jobbade (t.ex. möte) 💼")
        } else {
            arrayOf("Privat ärende 🚶", "Tandläkare / Läkare 🏥", "Lunch (tidig/sen) 🍔", "Annat ⚙️", "Nej, jag jobbade (t.ex. möte) 💼")
        }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Välkommen tillbaka! 👋")
            .setMessage("Du var borta från Wi-Fi i $durationMin minuter ($outStr - $inStr).\n\nVad var anledningen?")
            .setCancelable(false)
            .setItems(options) { _, which ->
                lifecycleScope.launch {
                    val repo = TimeRepository.getInstance(applicationContext)
                    
                    if (which == options.size - 1) {
                        // "Nej, jag jobbade" - Ta bort det felaktiga ut-logget
                        val lastLog = repo.getLastLog()
                        if (lastLog != null && lastLog.type.startsWith("UT")) {
                            repo.deleteLog(lastLog)
                        }
                    } else {
                        // Registrera frånvaron (behåll ut-logget och lägg till in-logget nu)
                        val reason = options[which].substringBefore(" ").trim()
                        repo.insertLog(WorkLog(type = "IN", timestamp = inTime, ssid = workplace, comment = "Efter $reason"))
                    }
                    viewModel.refreshData(0, -1)
                }
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

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Långt pass! ⏰")
            .setMessage("Du har varit inloggad på $workplace sedan kl $inStr.\n\nJobbar du fortfarande eller glömde du att stämpla ut?")
            .setCancelable(false)
            .setPositiveButton("JOBBAR ÄNNU") { dialog, _ -> dialog.dismiss() }
            .setNegativeButton("CHECK UT EFTER 8h") { dialog, _ ->
                lifecycleScope.launch {
                    val repo = TimeRepository.getInstance(applicationContext)
                    val outTime = inTime + (8 * 60 * 60 * 1000L)
                    repo.insertLog(WorkLog(type = "UT (Auto)", timestamp = outTime, ssid = workplace))
                    repo.setManualOverride(false)
                    viewModel.refreshData(0, -1)
                }
                dialog.dismiss()
            }
            .setNeutralButton("CHECK UT NU") { dialog, _ ->
                lifecycleScope.launch {
                    val repo = TimeRepository.getInstance(applicationContext)
                    repo.insertLog(WorkLog(type = "UT (Manuell)", timestamp = System.currentTimeMillis(), ssid = workplace))
                    repo.setManualOverride(false)
                    viewModel.refreshData(0, -1)
                }
                dialog.dismiss()
            }
            .show()
    }

    override fun onStart() {
        super.onStart()
    }

    override fun onStop() {
        super.onStop()
    }
}