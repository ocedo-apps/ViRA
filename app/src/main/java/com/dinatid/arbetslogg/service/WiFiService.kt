package com.dinatid.arbetslogg.service

import android.annotation.SuppressLint
import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.dinatid.arbetslogg.R
import com.dinatid.arbetslogg.WorkLog
import com.dinatid.arbetslogg.data.AppEvent
import com.dinatid.arbetslogg.data.DailyPattern
import com.dinatid.arbetslogg.data.TimeRepository
import com.dinatid.arbetslogg.ui.MainActivity
import kotlinx.coroutines.*
import java.util.*

@SuppressLint("MissingPermission", "NewApi")
class WiFiService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var disconnectTimestamp: Long = 0
    private val GRACE_PERIOD_MS = 5 * 60 * 1000L
    private var isConnectedToWorkWifi = false
    private var hasNotifiedOvertime = false
    private val OVERTIME_LIMIT_HOURS = 10.0
    private var lastLunchReminderDay = -1
    private var lastManualResetDay = -1
    private var lastLoginReminderDay = -1
    private var lastLogoutReminderDay = -1

    private lateinit var repository: TimeRepository

    private val actionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_LOGOUT_NOW -> {
                    Log.d("WiFiService", "Action: Logout Now")
                    performLogout(System.currentTimeMillis())
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.cancel(3) // Overtime
                    nm.cancel(5) // Logout nudge
                }
                ACTION_START_LUNCH -> {
                    Log.d("WiFiService", "Action: Start Lunch")
                    performLogout(System.currentTimeMillis())
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.cancel(4) // Lunch nudge
                }
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate() {
        super.onCreate()
        repository = TimeRepository.getInstance(applicationContext)

        val filter = IntentFilter().apply {
            addAction(ACTION_LOGOUT_NOW)
            addAction(ACTION_START_LUNCH)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(actionReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(actionReceiver, filter)
        }

        createNotificationChannel()
        startForeground(1, createNotification("Tjänsten är aktiv"))
        setupWifiMonitoring()
        startBackupCheckLoop()

        Log.d("ViRA_WIFI", "Tjänsten startad - Kör initial koll")
        checkCurrentWifi()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "FORCE_CHECK") {
            Log.d("ViRA_WIFI", "FORCE_CHECK mottagen från UI")
            checkCurrentWifi()
        }
        return START_STICKY
    }

    private fun setupWifiMonitoring() {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()

        connectivityManager.registerNetworkCallback(networkRequest, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d("ViRA_WIFI", "CALLBACK: onAvailable")
                checkCurrentWifi()
            }

            override fun onLost(network: Network) {
                Log.d("ViRA_WIFI", "CALLBACK: onLost")
                handleWifiLost()
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                Log.d("ViRA_WIFI", "CALLBACK: onCapabilitiesChanged")
                checkCurrentWifi()
            }
        })
    }

    private fun getWorkplaceNameForSsid(currentSsid: String): String? {
        val prefs = applicationContext.getSharedPreferences("arbetslogg_prefs", Context.MODE_PRIVATE)
        val savedData = prefs.getString("workplaces_structure", "") ?: ""

        if (savedData.isNotEmpty()) {
            val blocks = savedData.split("|")
            for (block in blocks) {
                val parts = block.split(":")
                if (parts.size == 2) {
                    val workplaceName = parts[0]
                    val ssids = parts[1].split(",").map { it.trim() }
                    if (ssids.any { it.equals(currentSsid, ignoreCase = true) }) {
                        return workplaceName
                    }
                }
            }
        }
        return null
    }

    private fun checkCurrentWifi() {
        try {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            
            val activeNetwork = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
            val isCurrentlyOnWifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

            var ssid = ""
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val wifiInfo = capabilities?.transportInfo as? WifiInfo
                ssid = wifiInfo?.ssid?.replace("\"", "")?.trim() ?: ""
            }
            if (ssid.isEmpty() || ssid == "<unknown ssid>") {
                @Suppress("DEPRECATION")
                val wifiInfo = wifiManager.connectionInfo
                ssid = wifiInfo.ssid?.replace("\"", "")?.trim() ?: ""
            }

            repository.setCurrentSsid(if (ssid.isEmpty()) null else ssid)

            val prefs = applicationContext.getSharedPreferences("arbetslogg_prefs", Context.MODE_PRIVATE)
            val smartHelpMode = prefs.getInt("smart_help_mode", 1)
            val workplaceName = getWorkplaceNameForSsid(ssid)
            
            // Säkerhetskoll: Nolla manuell override om det är en ny dag
            val today = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
            if (lastManualResetDay != today) {
                lastManualResetDay = today
                if (repository.isManualOverride()) {
                    Log.d("ViRA_WIFI", "Ny dag upptäckt! Nollar manual override.")
                    repository.setManualOverride(false)
                    repository.setManualOverrideSsid(null)
                }
            }

            Log.d("ViRA_WIFI", "KONTROLL -> SSID: '$ssid' | Arbetsplats: $workplaceName | SmartMode: $smartHelpMode")

            if (isCurrentlyOnWifi && ssid.isNotEmpty() && workplaceName != null) {
                isConnectedToWorkWifi = true
                repository.setWifiConnected(true)

                // Om vi är på en godkänd arbetsplats, kolla om vi nyligen loggat ut manuellt HÄR
                val manualOverrideSsid = repository.getManualOverrideSsid()
                if (manualOverrideSsid != null && ssid != manualOverrideSsid) {
                    repository.setManualOverrideSsid(null)
                    repository.setManualOverride(false)
                }

                if (disconnectTimestamp != 0L) {
                    serviceScope.launch {
                        val lastLog = repository.getLastLog()
                        val isManualLog = lastLog?.ssid?.startsWith("Manuell", ignoreCase = true) == true
                        if (smartHelpMode == 2 && lastLog?.ssid != workplaceName && lastLog?.ssid != null && !isManualLog) {
                            performLogout(disconnectTimestamp)
                            performLogin(workplaceName)
                        }
                        disconnectTimestamp = 0L
                        sendCountdownUpdate(-1)
                    }
                } else {
                    if (repository.isManualOverride() && ssid == repository.getManualOverrideSsid()) {
                        Log.d("ViRA_WIFI", "Manual override aktiv för $ssid. Skippar auto.")
                    } else {
                        repository.setManualOverride(false)
                        repository.setManualOverrideSsid(null)

                        serviceScope.launch {
                            val lastLog = repository.getLastLog()
                            if (lastLog?.type != WorkLog.TYPE_IN) {
                                val now = System.currentTimeMillis()
                                if (smartHelpMode == 1 && lastLog != null && lastLog.type.startsWith(WorkLog.TYPE_OUT)) {
                                    val gapMin = (now - lastLog.timestamp) / 60000
                                    
                                    // Fråga om alla gap över 20 minuter
                                    if (gapMin >= 20) {
                                        askGapQuestion(workplaceName, lastLog.timestamp, now)
                                        return@launch
                                    }
                                }
                                performLogin(workplaceName)
                            }
                        }
                    }
                }
            } else {
                if (isCurrentlyOnWifi && (ssid == "<unknown ssid>" || ssid.contains("unknown", ignoreCase = true))) return

                val wasPreviouslyConnected = isConnectedToWorkWifi
                isConnectedToWorkWifi = false
                repository.setWifiConnected(false)
                
                // Om vi lämnat Wi-Fi nollställer vi override så vi är redo för nästa plats/dag
                if (wasPreviouslyConnected) {
                    repository.setManualOverride(false)
                    repository.setManualOverrideSsid(null)
                }

                serviceScope.launch {
                    val lastLog = repository.getLastLog()
                    val isManualOther = lastLog?.ssid?.contains("Övrigt", ignoreCase = true) == true
                    if (lastLog?.type == "IN" && (wasPreviouslyConnected || !isManualOther)) {
                        if (disconnectTimestamp == 0L) handleWifiLost()
                    } else {
                        if (disconnectTimestamp != 0L) {
                            disconnectTimestamp = 0L
                            updateNotification("Tjänsten är aktiv")
                        }
                        sendCountdownUpdate(-1)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ViRA_WIFI", "Fel: ${e.message}")
        }
    }

    private fun handleWifiLost() {
        val prefs = applicationContext.getSharedPreferences("arbetslogg_prefs", Context.MODE_PRIVATE)
        if (prefs.getInt("smart_help_mode", 1) == 0) {
            performLogout(System.currentTimeMillis())
        } else if (disconnectTimestamp == 0L) {
            disconnectTimestamp = System.currentTimeMillis()
            updateNotification("Loggar ut automatiskt om 5 min...")
            notifyDataChanged()
        }
    }

    private fun startBackupCheckLoop() {
        serviceScope.launch {
            var lastDbCheckTime = 0L
            while (isActive) {
                val now = System.currentTimeMillis()
                if (disconnectTimestamp != 0L) {
                    val elapsed = now - disconnectTimestamp
                    if (elapsed >= GRACE_PERIOD_MS) {
                        performLogout(disconnectTimestamp)
                        disconnectTimestamp = 0L
                        sendCountdownUpdate(-1)
                    } else {
                        sendCountdownUpdate(((GRACE_PERIOD_MS - elapsed) / 1000).toInt())
                    }
                }
                if (now - lastDbCheckTime >= 60000L) {
                    lastDbCheckTime = now
                    checkCurrentWifi()
                    val lastLog = repository.getLastLog()
                    val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                    val currentMin = Calendar.getInstance().get(Calendar.MINUTE)
                    val currentTimeMins = currentHour * 60 + currentMin
                    val currentDay = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)

                    // Hämta mönster för IDAG
                    val fullPattern = repository.getUserPattern()
                    val todayWeekday = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
                    val pattern = fullPattern.weekdayPatterns[todayWeekday] ?: DailyPattern(null, null, null, null, 0f)

                    if (lastLog?.type == "IN") {
                        val durationHrs = (now - lastLog.timestamp) / 3600000.0
                        if (durationHrs >= OVERTIME_LIMIT_HOURS && !hasNotifiedOvertime) {
                            hasNotifiedOvertime = true
                            askOvertimeQuestion(lastLog.ssid ?: "Jobbet", lastLog.timestamp)
                        }

                        // Smart Lunchpåminnare
                        val lunchStart = pattern.avgLunchStartMins ?: (12 * 60)
                        val isAtLunchTime = currentTimeMins in (lunchStart - 10)..(lunchStart + 60)
                        val hasWorkedLongEnough = durationHrs >= 4.5
                        
                        if (isAtLunchTime && hasWorkedLongEnough && lastLunchReminderDay != currentDay) {
                            lastLunchReminderDay = currentDay
                            sendFriendlyLunchNudge(pattern.avgLunchStartMins != null)
                        }
                        
                        // Smart Hemgångspåminnare
                        if (pattern.avgDepartureMins != null && currentTimeMins > (pattern.avgDepartureMins + 45) && lastLogoutReminderDay != currentDay) {
                            lastLogoutReminderDay = currentDay
                            sendReminderNotification(
                                getString(R.string.reminder_logout_title),
                                getString(R.string.reminder_logout_msg),
                                5
                            )
                        }
                    } else {
                        hasNotifiedOvertime = false
                        
                        // Smart Inloggningspåminnare
                        // Endast om vi faktiskt brukar jobba denna veckodag
                        if (pattern.workProbability > 0.5f && pattern.avgArrivalMins != null && 
                            currentTimeMins > (pattern.avgArrivalMins + 20) && currentTimeMins < (pattern.avgArrivalMins + 120)) {
                             if (lastLoginReminderDay != currentDay) {
                                 lastLoginReminderDay = currentDay
                                 val timeStr = String.format(Locale.getDefault(), "%02d:%02d", pattern.avgArrivalMins / 60, pattern.avgArrivalMins % 60)
                                 sendReminderNotification(
                                     getString(R.string.reminder_login_title),
                                     getString(R.string.reminder_login_msg, timeStr),
                                     6
                                 )
                             }
                        }
                    }
                }
                delay(if (disconnectTimestamp != 0L) 1000 else 10000)
            }
        }
    }

    private fun askGapQuestion(workplace: String, outTime: Long, inTime: Long) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("show_gap_dialog", true)
            putExtra("workplace", workplace)
            putExtra("out_time", outTime)
            putExtra("in_time", inTime)
        }
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        
        val outCal = Calendar.getInstance().apply { timeInMillis = outTime }
        val timeOfDay = outCal.get(Calendar.HOUR_OF_DAY) + (outCal.get(Calendar.MINUTE) / 60.0)
        val isLunchTime = timeOfDay in 10.5..13.5

        val title = if (isLunchTime) "Lunchrast? 🍔" else "Frånvaro detekterad ⏱️"
        val text = if (isLunchTime) "Klicka här för att registrera lunch." else "Du var borta en stund. Klicka för att ange orsak."

        val notif = NotificationCompat.Builder(this, "smarthelpchannel")
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pi).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH).build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(2, notif)
    }

    private fun askOvertimeQuestion(workplace: String, inTime: Long) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("show_overtime_dialog", true)
            putExtra("workplace", workplace)
            putExtra("in_time", inTime)
        }
        val pi = PendingIntent.getActivity(this, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        
        val logoutIntent = Intent(ACTION_LOGOUT_NOW)
        val logoutPi = PendingIntent.getBroadcast(this, 10, logoutIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val notif = NotificationCompat.Builder(this, "smarthelpchannel")
            .setContentTitle("Glömt stämpla ut? ⏰")
            .setContentText("Du har varit inloggad i över 10 timmar.")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, "CHECK UT NU", logoutPi)
            .build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(3, notif)
    }

    private fun sendFriendlyLunchNudge(isLearned: Boolean) {
        val activityIntent = Intent(this, com.dinatid.arbetslogg.ui.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(this, 2, activityIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val lunchIntent = Intent(ACTION_START_LUNCH)
        val lunchPi = PendingIntent.getBroadcast(this, 11, lunchIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val msg = if (isLearned) getString(R.string.reminder_lunch_msg) else getString(R.string.pepp_lunch)

        val notification = NotificationCompat.Builder(this, "smarthelpchannel")
            .setContentTitle("Dags för lunch? 🥪")
            .setContentText(msg)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, "STARTA LUNCH", lunchPi)
            .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(4, notification)
    }

    private fun sendReminderNotification(title: String, message: String, id: Int) {
        val activityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(this, id, activityIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(this, "smarthelpchannel")
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        if (id == 5) { // Logout reminder
            val logoutIntent = Intent(ACTION_LOGOUT_NOW)
            val logoutPi = PendingIntent.getBroadcast(this, 12, logoutIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(0, "CHECK UT NU", logoutPi)
        }

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(id, builder.build())
    }

    private fun performLogin(workplaceName: String) {
        serviceScope.launch {
            val lastLog = repository.getLastLog()
            if (lastLog?.type != WorkLog.TYPE_IN) {
                repository.insertLog(WorkLog(type = WorkLog.TYPE_IN, timestamp = System.currentTimeMillis(), ssid = workplaceName))
                updateNotification("Incheckad på $workplaceName (Auto)")
                notifyDataChanged()
            }
        }
    }

    private fun performLogout(effectiveTime: Long) {
        serviceScope.launch {
            val lastLog = repository.getLastLog()
            if (lastLog?.type == WorkLog.TYPE_IN) {
                repository.insertLogoutWithMidnightSplit(lastLog.timestamp, effectiveTime, lastLog.ssid ?: "Okänd", false)
                updateNotification("Utcheckad (Auto)")
                notifyDataChanged()
            }
        }
    }

    private fun notifyDataChanged() {
        serviceScope.launch {
            repository.emitEvent(AppEvent.RefreshData)
        }
    }

    private fun sendCountdownUpdate(secondsLeft: Int) {
        serviceScope.launch {
            repository.emitEvent(AppEvent.CountdownUpdate(secondsLeft))
        }
    }

    private fun createNotification(content: String): Notification {
        return NotificationCompat.Builder(this, "wifimonitorchannel")
            .setContentTitle("ViRA").setContentText(content)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW).setOngoing(true).build()
    }

    private fun updateNotification(content: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(1, createNotification(content))
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("wifimonitorchannel", "Wi-Fi", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel("smarthelpchannel", "SmartHelp", NotificationManager.IMPORTANCE_HIGH))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(actionReceiver)
        serviceScope.cancel()
    }

    companion object {
        private const val ACTION_LOGOUT_NOW = "com.dinatid.arbetslogg.ACTION_LOGOUT_NOW"
        private const val ACTION_START_LUNCH = "com.dinatid.arbetslogg.ACTION_START_LUNCH"
    }
}