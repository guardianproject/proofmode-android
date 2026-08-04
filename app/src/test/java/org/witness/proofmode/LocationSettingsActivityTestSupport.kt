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

    fun reflectiveProofModeApp(context: Context): ProofModeApp =
        ProofModeApp::class.java.getDeclaredConstructor().newInstance().also { instance ->
            val attach = android.content.ContextWrapper::class.java.getDeclaredMethod(
                "attachBaseContext",
                Context::class.java,
            )
            attach.isAccessible = true
            attach.invoke(instance, context)
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
}
