package org.witness.proofmode.plugins.lp.wallet

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * UI contract for the ZeroDev project ID field (layout + strings).
 *
 * Uses source-file assertions rather than Robolectric inflate because the host JDK 25
 * fails Robolectric teardown (`RoboCookieManager`) even when assertions pass.
 */
class WalletSponsorshipProjectIdFieldUiTest {

    private val moduleRoot: File
        get() {
            var dir = File(System.getProperty("user.dir")!!)
            repeat(4) {
                val nested = File(dir, "plugin-location-protocol/src/main/res")
                if (nested.isDirectory) return File(dir, "plugin-location-protocol")
                if (File(dir, "src/main/res").isDirectory && dir.name == "plugin-location-protocol") {
                    return dir
                }
                dir = dir.parentFile ?: return@repeat
            }
            error("Could not locate plugin-location-protocol module root from ${System.getProperty("user.dir")}")
        }

    @Test
    fun projectIdLabel_isOverrideZeroDevProjectId() {
        val strings = File(moduleRoot, "src/main/res/values/strings.xml").readText()
        assertTrue(
            strings.contains(
                """<string name="wallet_zerodev_project_id_label">Override ZeroDev Project ID</string>""",
            ),
        )
    }

    @Test
    fun projectIdEditText_usesPasswordVariation_withoutToggleEndIcon() {
        val layout = File(moduleRoot, "src/main/res/layout/activity_wallet_settings.xml").readText()
        assertTrue(layout.contains("""android:id="@+id/et_zerodev_project_id""""))
        assertTrue(layout.contains("""android:inputType="textPassword""""))
        assertFalse(
            "password toggle / endIconMode must stay off",
            layout.contains("password_toggle") || layout.contains("endIconMode"),
        )
    }

    @Test
    fun projectIdHelperView_isRemovedFromLayout() {
        val layout = File(moduleRoot, "src/main/res/layout/activity_wallet_settings.xml").readText()
        assertFalse(layout.contains("tv_zerodev_project_id_helper"))
        assertTrue(layout.contains("""android:id="@+id/et_zerodev_project_id""""))
    }

    @Test
    fun helperUuidString_isRemoved() {
        val strings = File(moduleRoot, "src/main/res/values/strings.xml").readText()
        assertFalse(strings.contains("wallet_zerodev_project_id_helper_default"))
        assertFalse(strings.contains("wallet_zerodev_project_id_helper_override"))
        assertFalse(strings.contains("wallet_zerodev_project_id_helper_no_build_default"))
        assertTrue(strings.contains("wallet_zerodev_project_id_error_invalid"))
        assertTrue(strings.contains("wallet_zerodev_project_id_saved"))
    }
}
