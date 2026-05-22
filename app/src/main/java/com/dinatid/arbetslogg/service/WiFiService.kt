package com.dinatid.arbetslogg.service

import android.app.*
import android.content.Context
import android.content.Intent
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
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.dinatid.arbetslogg.R
import com.dinatid.arbetslogg.WorkLog
import com.dinatid.arbetslogg.data.TimeRepository
import com.dinatid.arbetslogg.ui.MainActivity
import kotlinx.coroutines.*
import java.util.*

class WiFiService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var disconnectTimestamp: Long = 0
    private val GRACE_PERIOD_MS = 5 * 60 * 1000L
    private var isConnectedToWorkWifi = false
    private var hasNotifiedOvertime = false
    private val OVERTIME_LIMIT_HOURS = 10.0 // Larmar efter 10 timmar
    private var lastLunchReminderDay = -1 // Håller koll på vilken dag vi senast larmade

    private lateinit var repository: TimeRepository

    override fun onCreate() {
        super.onCreate()
        repository = TimeRepository(applicationContext)

        createNotificationChannel()
        startForeground(1, createNotification("Tjänsten är aktiv"))
        setupWifiMonitoring()
        startBackupCheckLoop()

        Log.d("ARBETSLOGG_WIFI", "Tjänsten onCreate körs - kollar Wi-Fi direkt")
        checkCurrentWifi()
    }

    private fun setupWifiMonitoring() {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        connectivityManager.registerNetworkCallback(networkRequest, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d("ARBETSLOGG_WIFI", "ANSLUTEN: Telefonen hittade ett Wi-Fi!")
                checkCurrentWifi()
            }

            override fun onLost(network: Network) {
                Log.d("ARBETSLOGG_WIFI", "TAPPAT: Wi-Fi-anslutningen bröts helt!")
                handleWifiLost()
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
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
            val activeNetwork = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
            val isCurrentlyOnWifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val info: WifiInfo? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                capabilities?.transportInfo as? WifiInfo
            } else {
                @Suppress("DEPRECATION")
                wifiManager.connectionInfo
            }
            val ssid = info?.ssid?.replace("\"", "")?.trim() ?: ""

            val prefs = applicationContext.getSharedPreferences("arbetslogg_prefs", Context.MODE_PRIVATE)
            val smartHelpMode = prefs.getInt("smart_help_mode", 1)
            val workplaceName = getWorkplaceNameForSsid(ssid)

            Log.d("ARBETSLOGG_WIFI", "Status -> Wi-Fi: $isCurrentlyOnWifi | SSID: '$ssid' | Arbetsplats: $workplaceName | SmartMode: $smartHelpMode")

            if (isCurrentlyOnWifi && ssid.isNotEmpty() && workplaceName != null) {
                // --- VI ÄR PÅ EN GODKÄND ARBETSPLATS ---
                isConnectedToWorkWifi = true
                repository.setWifiConnected(true)

                if (disconnectTimestamp != 0L) {
                    serviceScope.launch {
                        val lastLog = repository.getLastLog()
                        val isManualLog = lastLog?.ssid?.startsWith("Manuell", ignoreCase = true) == true

                        // LÄGE 2 (KONSULT): Kundbyte på under 5 minuter (Ignorera om manuell)
                        if (smartHelpMode == 2 && lastLog?.ssid != workplaceName && lastLog?.ssid != null && !isManualLog) {
                            Log.d("ARBETSLOGG_WIFI", "KONSULT: Kundbyte upptäckt! Gick från ${lastLog.ssid} till $workplaceName")
                            performLogout(disconnectTimestamp)
                            performLogin(workplaceName)
                        } else {
                            Log.d("ARBETSLOGG_WIFI", "Välkommen tillbaka till $workplaceName. Rensar utchecknings-timern.")
                        }

                        disconnectTimestamp = 0L
                        sendCountdownUpdate(-1)
                    }
                } else {
                    if (repository.isManualOverride()) {
                        Log.d("ARBETSLOGG_WIFI", "Manual override aktiv. Skippar auto-inlogg.")
                    } else {
                        serviceScope.launch {
                            val lastLog = repository.getLastLog()
                            val now = System.currentTimeMillis()

                            // LUCK-DETEKTORN MED TIDSFÖNSTER
                            // --- NY OCH FÖRBÄTTRAD SMARTHELP (LUNCH-DETEKTORN) ---
                            if (smartHelpMode == 1 && lastLog != null && lastLog.type.startsWith("UT")) {
                                val gapMs = now - lastLog.timestamp
                                val gapMinutes = gapMs / (60 * 1000)

                                // Få ut tiden för UT-loggningen i decimal (t.ex. 10:30 blir 10.5)
                                val outCal = Calendar.getInstance().apply { timeInMillis = lastLog.timestamp }
                                val outHour = outCal.get(Calendar.HOUR_OF_DAY)
                                val outMinute = outCal.get(Calendar.MINUTE)
                                val timeOfDay = outHour + (outMinute / 60.0)

                                // 1. Tidsfönster: Startade avbrottet mellan 10:30 och 13:30?
                                val isLunchTimeWindow = timeOfDay in 10.5..13.5

                                // 2. Längd: Var avbrottet mellan 30 och 90 minuter?
                                val isLunchDuration = gapMinutes in 30..90

                                // 3. Plats: Var vi på samma arbetsplats när vi checkade ut?
                                val isSameWorkplace = lastLog.ssid.equals(workplaceName, ignoreCase = true)

                                if (isLunchTimeWindow && isLunchDuration && isSameWorkplace) {
                                    Log.d("ARBETSLOGG_WIFI", "SMARTHELP: Perfekt lunchavbrott upptäckt! ($gapMinutes min kl $outHour:$outMinute). Skickar notis!")

                                    // Avfyra notisen till användaren!
                                    askLunchQuestion(workplaceName, lastLog.timestamp, now)

                                    // VIKTIGT: We abort here to wait for user dialog response
                                    return@launch
                                } else {
                                    Log.d("ARBETSLOGG_WIFI", "SMARTHELP: Avbrottet passade inte som lunch. Loggar in normalt.")
                                }
                            }

                            // --- FIX: Den dubbelklistrade performLogin-raden är nu borta! ---
                            performLogin(workplaceName)
                        }
                    }
                }
            } else {
                // --- VI ÄR INTE PÅ EN GODKÄND ARBETSPLATS ELLER SAKNAR WI-FI ---
                if (isCurrentlyOnWifi && (ssid == "<unknown ssid>" || ssid.contains("unknown", ignoreCase = true))) {
                    return
                }

                isConnectedToWorkWifi = false
                repository.setWifiConnected(false)
                repository.setManualOverride(false)

                serviceScope.launch {
                    val lastLog = repository.getLastLog()
                    val isManualLog = lastLog?.ssid?.startsWith("Manuell", ignoreCase = true) == true

                    if (lastLog?.type == "IN" && !isManualLog) {
                        if (disconnectTimestamp == 0L) {
                            handleWifiLost()
                        }
                    } else {
                        disconnectTimestamp = 0L
                        sendCountdownUpdate(-1)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ARBETSLOGG_WIFI", "Fel i checkCurrentWifi: ${e.message}", e)
        }
    }

    private fun askLunchQuestion(workplace: String, outTime: Long, inTime: Long) {
        val activityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("show_lunch_dialog", true)
            putExtra("workplace", workplace)
            putExtra("out_time", outTime)
            putExtra("in_time", inTime)
        }

        val pendingIntent = PendingIntent.getActivity(this, 0, activityIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(this, "smarthelpchannel")
            .setContentTitle("Lunchrast? 🍔")
            .setContentText("Du var iväg en stund. Klicka här för att registrera lunch.")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(2, notification)
    }

    private fun handleWifiLost() {
        val prefs = applicationContext.getSharedPreferences("arbetslogg_prefs", Context.MODE_PRIVATE)
        val smartHelpMode = prefs.getInt("smart_help_mode", 1)

        if (smartHelpMode == 0) {
            Log.d("ARBETSLOGG_WIFI", "SMARTHELP AV: Tappade Wi-Fi. Checkar ut direkt utan buffert.")
            performLogout(System.currentTimeMillis())
        } else {
            if (disconnectTimestamp == 0L) {
                disconnectTimestamp = System.currentTimeMillis()
                updateNotification("Loggar ut automatiskt om 5 min...")
                Log.d("ARBETSLOGG_WIFI", "Nedräkningstimer startad vid timestamp: $disconnectTimestamp")
                // Meddela UI direkt att nedräkningen börjat
                notifyDataChanged()
            }
        }
    }

    // --- FIX: Den nya, extremt batterisnåla backup-loopen med dynamisk delay! ---
    private fun startBackupCheckLoop() {
        serviceScope.launch {
            var lastDbCheckTime = 0L

            while (isActive) {
                val now = System.currentTimeMillis()

                // 1. WI-FI TIMEOUT-NEDRÄKNING (Kollas varje sekund om aktiv)
                if (disconnectTimestamp != 0L) {
                    val elapsed = now - disconnectTimestamp
                    val secondsLeft = ((GRACE_PERIOD_MS - elapsed) / 1000).toInt()

                    if (elapsed >= GRACE_PERIOD_MS) {
                        Log.d("ARBETSLOGG_WIFI", "BINGO! 5 minuter har gått. Loggar ut nu.")
                        performLogout(disconnectTimestamp)
                        disconnectTimestamp = 0
                        isConnectedToWorkWifi = false
                        sendCountdownUpdate(-1)
                    } else {
                        sendCountdownUpdate(secondsLeft)
                    }
                }

                // 2. TYNGRE KOLLAR (Körs nu bara en gång i minuten istället för varje sekund!)
                if (now - lastDbCheckTime >= 60000L) {
                    lastDbCheckTime = now
                    
                    // Backup-koll av Wi-Fi om systemet missat att skicka callback
                    checkCurrentWifi()

                    val lastLog = repository.getLastLog()
                    val nowCal = Calendar.getInstance()
                    val currentHour = nowCal.get(Calendar.HOUR_OF_DAY)
                    val currentDay = nowCal.get(Calendar.DAY_OF_YEAR)

                    // Övertidsvarnaren
                    if (lastLog != null && lastLog.type == "IN") {
                        val durationMs = now - lastLog.timestamp
                        val durationHours = durationMs / (1000 * 60 * 60.0)

                        if (durationHours >= OVERTIME_LIMIT_HOURS && !hasNotifiedOvertime) {
                            hasNotifiedOvertime = true
                            askOvertimeQuestion(lastLog.ssid ?: "Jobbet", lastLog.timestamp)
                        }
                    } else {
                        hasNotifiedOvertime = false
                    }

                    // Lunchpåminnare (Klockan 12:00)
                    if (lastLog != null && lastLog.type == "IN" && currentHour == 12) {
                        if (lastLunchReminderDay != currentDay) {
                            lastLunchReminderDay = currentDay
                            sendFriendlyLunchNudge()
                        }
                    }
                }

                // Dynamisk vila beroende på om nedräkning pågår eller inte
                if (disconnectTimestamp != 0L) {
                    delay(1000)
                } else {
                    delay(5000)
                }
            }
        }
    }

    private fun askOvertimeQuestion(workplace: String, inTime: Long) {
        val activityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("show_overtime_dialog", true)
            putExtra("workplace", workplace)
            putExtra("in_time", inTime)
        }
        val pendingIntent = PendingIntent.getActivity(this, 1, activityIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(this, "smarthelpchannel")
            .setContentTitle("Glömt stämpla ut? ⏰")
            .setContentText("Du har varit inloggad i över 10 timmar. Klicka för att granska!")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(3, notification)
    }

    private fun sendFriendlyLunchNudge() {
        val activityIntent = Intent(this, com.dinatid.arbetslogg.ui.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(this, 2, activityIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        // --- FIX: Ändrat kanal-ID från stavfelet till det korrekta "smarthelpchannel" ---
        val notification = NotificationCompat.Builder(this, "smarthelpchannel")
            .setContentTitle("Dags för lunch? 🥪")
            .setContentText("Om du tar rast nu, glöm inte att stämpla ut i appen!")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notificationManagerInstance = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManagerInstance.notify(4, notification)
    }

    private fun performLogin(workplaceName: String) {
        serviceScope.launch {
            val lastLog = repository.getLastLog()
            if (lastLog?.type != "IN") {
                Log.d("ARBETSLOGG_WIFI", "Skriver IN-logg till databasen för: $workplaceName")
                repository.insertLog(WorkLog(type = "IN", timestamp = System.currentTimeMillis(), ssid = workplaceName))
                updateNotification("Incheckad på $workplaceName (Auto)")
                notifyDataChanged()
            }
        }
    }

    // --- FIX: Här rök nästan 35 rader kod! All kalendermatematik körs nu säkert via Repositoryt ---
    private fun performLogout(effectiveTime: Long) {
        serviceScope.launch {
            val lastLog = repository.getLastLog()
            if (lastLog?.type == "IN") {
                val inTime = lastLog.timestamp
                val outTime = effectiveTime
                val workplaceName = lastLog.ssid ?: "Okänd arbetsplats"

                // Anropa den centraliserade midnattssplitten i ditt repository!
                repository.insertLogoutWithMidnightSplit(inTime, outTime, workplaceName, false)

                Log.d("ARBETSLOGG_WIFI", "Utloggningslogg sparad i DB via Repositoryt.")
                updateNotification("Utcheckad från $workplaceName (Auto)")
                notifyDataChanged()
            }
        }
    }

    private fun notifyDataChanged() {
        val intent = Intent("REFRESH_DATA")
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun sendCountdownUpdate(secondsLeft: Int) {
        val intent = Intent("COUNTDOWN_UPDATE")
        intent.putExtra("seconds_left", secondsLeft)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun createNotification(content: String): Notification {
        return NotificationCompat.Builder(this, "wifimonitorchannel")
            .setContentTitle("Arbetslogg")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(content: String) {
        val notification = createNotification(content)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(1, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)

            val channelLow = NotificationChannel(
                "wifimonitorchannel",
                "Wi-Fi Övervakning",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channelLow)

            val channelHigh = NotificationChannel(
                "smarthelpchannel",
                "SmartHelp Påminnelser",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                enableVibration(true)
                setShowBadge(true)
            }
            manager.createNotificationChannel(channelHigh)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d("ARBETSLOGG_WIFI", "Tjänsten stoppas (onDestroy)")
        serviceScope.cancel()
    }
}