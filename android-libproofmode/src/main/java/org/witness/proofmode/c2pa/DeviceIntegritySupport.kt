package org.witness.proofmode.c2pa

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.provider.Settings
import java.io.File
import java.net.Socket

class DeviceIntegritySupport {

    fun detectThreats (context: Context): Boolean {
        return isUsbConnected(context) || isDeveloperAttackSurfaceOpen(context) || isFridaPresent()
    }


    fun isUsbConnected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        // USB tethering shows as an ethernet-type network
        cm.allNetworks.forEach { network ->
            val caps = cm.getNetworkCapabilities(network)
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) return true
        }

        // Also check battery manager for USB power source
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS) != -1 &&
                isUsbPowerSource(context)
    }

    fun isUsbPowerSource(context: Context): Boolean {
        val intent = context.registerReceiver(null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
        return plugged == BatteryManager.BATTERY_PLUGGED_USB
    }

    fun isDeveloperAttackSurfaceOpen(context: Context): Boolean {
        val adbEnabled = Settings.Global.getInt(
            context.contentResolver,
            Settings.Global.ADB_ENABLED, 0
        ) == 1
        val devOptions = Settings.Global.getInt(
            context.contentResolver,
            Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0
        ) == 1
        return adbEnabled || devOptions
    }

    fun isFridaPresent(): Boolean {
        // Check open ports
        try {
            Socket("127.0.0.1", 27042).use { return true }
        } catch (_: Exception) {}

        var foundFrida = false
        // Check /proc/self/maps for injected libraries
        File("/proc/self/maps").forEachLine { line ->
            if (line.contains("frida") || line.contains("gum-js")) {
                foundFrida = true
            }
        }
        return foundFrida
    }

}