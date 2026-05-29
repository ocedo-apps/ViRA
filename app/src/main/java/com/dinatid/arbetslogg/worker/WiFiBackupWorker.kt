package com.dinatid.arbetslogg.worker

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.dinatid.arbetslogg.WorkLog
import com.dinatid.arbetslogg.data.AppEvent
import com.dinatid.arbetslogg.data.TimeRepository

@SuppressLint("MissingPermission", "NewApi")
class WiFiBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val repository = TimeRepository.getInstance(context)

    override suspend fun doWork(): Result {
        Log.d("WiFiBackupWorker", "Kör periodisk bakgrundskontroll...")
        
        try {
            checkAndSyncStatus()
            return Result.success()
        } catch (e: Exception) {
            Log.e("WiFiBackupWorker", "Fel vid bakgrundskontroll: ${e.message}")
            return Result.retry()
        }
    }

    private suspend fun checkAndSyncStatus() {
        val ssid = getCurrentSsid()
        val workplaceName = getWorkplaceNameForSsid(ssid)
        val lastLog = repository.getLastLog()
        val isCurrentlyIn = lastLog?.type == WorkLog.TYPE_IN
        val now = System.currentTimeMillis()

        Log.d("WiFiBackupWorker", "Status -> SSID: '$ssid' | Arbetsplats: $workplaceName | Loggad in: $isCurrentlyIn")

        if (workplaceName != null && !isCurrentlyIn) {
            // Vi är på jobbet men utloggade -> Auto incheckning (om inte manual override)
            if (!repository.isManualOverride()) {
                Log.i("WiFiBackupWorker", "Väcker systemet: Loggar in på $workplaceName")
                repository.insertLog(WorkLog(type = WorkLog.TYPE_IN, timestamp = now, ssid = workplaceName))
                repository.emitEvent(AppEvent.RefreshData)
            }
        } else if (workplaceName == null && isCurrentlyIn) {
            // Vi är INTE på jobbet men inloggade -> Auto utcheckning (om det inte var en manuell 'Övrigt'-inloggning)
            val isManualOther = lastLog?.ssid?.contains("Övrigt", ignoreCase = true) == true
            if (!isManualOther) {
                Log.i("WiFiBackupWorker", "Väcker systemet: Loggar ut från ${lastLog?.ssid}")
                repository.insertLogoutWithMidnightSplit(lastLog?.timestamp ?: now, now, lastLog?.ssid ?: "Okänd", false)
                repository.emitEvent(AppEvent.RefreshData)
            }
        }
    }

    private fun getCurrentSsid(): String {
        val connectivityManager = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        
        val activeNetwork = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
        val isCurrentlyOnWifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

        if (!isCurrentlyOnWifi) return ""

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
        return ssid
    }

    private fun getWorkplaceNameForSsid(currentSsid: String): String? {
        if (currentSsid.isEmpty() || currentSsid.contains("unknown", ignoreCase = true)) return null
        
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
}
