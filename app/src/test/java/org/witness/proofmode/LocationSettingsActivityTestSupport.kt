package org.witness.proofmode

import android.app.Activity
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.robolectric.Robolectric
import org.witness.proofmode.plugins.lp.LocationProtocolPlugin
import org.witness.proofmode.util.TestWalletStackHelper

internal object LocationSettingsActivityTestSupport {

    // The handler can legitimately be null, so a separate flag marks "already saved".
    private var savedDefaultHandler: Thread.UncaughtExceptionHandler? = null
    private var defaultHandlerSaved = false

    fun reflectiveProofModeApp(context: Context): ProofModeApp {
        // attachBaseContext installs ACRA as the JVM-wide default handler. Saved once, before the first
        // install: a later call would capture ACRA itself, and a leftover ACRA ends the worker JVM when
        // an unrelated class's background thread throws.
        if (!defaultHandlerSaved) {
            savedDefaultHandler = Thread.getDefaultUncaughtExceptionHandler()
            defaultHandlerSaved = true
        }
        return ProofModeApp::class.java.getDeclaredConstructor().newInstance().also { instance ->
            val attach = android.content.ContextWrapper::class.java.getDeclaredMethod(
                "attachBaseContext",
                Context::class.java,
            )
            attach.isAccessible = true
            attach.invoke(instance, context)
        }
    }

    fun tearDown() {
        if (defaultHandlerSaved) {
            Thread.setDefaultUncaughtExceptionHandler(savedDefaultHandler)
            savedDefaultHandler = null
            defaultHandlerSaved = false
        }
    }

    fun launchLocationSettingsActivity(): LocationSettingsActivity {
        val context: Context = ApplicationProvider.getApplicationContext()
        val app = reflectiveProofModeApp(context)
        LocationProtocolPlugin.registerApplicationScope(
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
        ExperimentalFeatureActivator.resetActivationStateForTests()
        ExperimentalFeatureActivator.resetActivationGuardsForTests()
        TestWalletStackHelper.resetWalletStack()
        val controller: org.robolectric.android.controller.ActivityController<LocationSettingsActivity> =
            Robolectric.buildActivity(LocationSettingsActivity::class.java).create()
        bindApplication(controller.get(), app)
        return controller.start().resume().get()
    }

    fun bindApplication(activity: Activity, app: ProofModeApp) {
        val field = Activity::class.java.getDeclaredField("mApplication")
        field.isAccessible = true
        field.set(activity, app)
    }

    fun fragment(activity: LocationSettingsActivity): LocationSettingsActivity.LocationPreferencesFragment =
        activity.supportFragmentManager
            .findFragmentById(R.id.location_settings_container)
            as LocationSettingsActivity.LocationPreferencesFragment
}
