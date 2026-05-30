package org.witness.proofmode.c2pa

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.provider.Settings
import info.guardianproject.durindoor.Native
import java.io.File
import java.net.Socket

class DeviceIntegritySupport {

    fun detectThreats (context: Context): Boolean {
        return isUsbConnected(context) || isDeveloperAttackSurfaceOpen(context) || isFridaPresent()
    }

    fun isEnvironmentCompromised (): Boolean {
        return nativeAvailable &&
            runCatching { Native.nativeIsEnvironmentCompromised() }.getOrDefault(false)
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
        // Native checks are harder for Frida to hook than the JVM equivalents:
        // /proc/self/maps via fopen, /proc/net/tcp[6] port scan, and a
        // /proc/self/task comm-name scan for injected helper threads. These now
        // live in the closed-source durindoor library (info.guardianproject:durindoor).
        if (nativeAvailable && runCatching { Native.nativeIsEnvironmentCompromised() }.getOrDefault(false)) {
            return true
        }

        // JVM-side fallback (trivially hookable, but cheap belt-and-suspenders).
        try {
            Socket("127.0.0.1", 27042).use { return true }
        } catch (_: Exception) {}

        var foundFrida = false
        File("/proc/self/maps").forEachLine { line ->
            if (line.contains("frida") || line.contains("gum-js")) {
                foundFrida = true
            }
        }
        return foundFrida
    }

    companion object {
        // Touching durindoor's Native triggers its System.loadLibrary("durindoor"),
        // which runs the load-time integrity tripwire. Native.loaded is false if
        // the .so is absent (e.g. an unshipped ABI) or refused to load.
        private val nativeAvailable: Boolean = runCatching { Native.loaded }.getOrDefault(false)

        /**
         * Forces libdurindoor to load now — which runs its .init_array /
         * JNI_OnLoad load-time integrity tripwire (root + instrumentation
         * checks) and arms the anti-debug watchdog — instead of lazily on the
         * first native call. In release builds the tripwire kills the process
         * from native code if it detects root or Frida. Call this as early as
         * possible in Application startup.
         */
        @JvmStatic
        fun ensureNativeLoaded(): Boolean = nativeAvailable
    }

}