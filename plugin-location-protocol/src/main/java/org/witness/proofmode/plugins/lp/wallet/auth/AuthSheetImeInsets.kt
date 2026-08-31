package org.witness.proofmode.plugins.lp.wallet.auth

import android.view.View
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import com.google.android.material.bottomsheet.BottomSheetDialog

object AuthSheetImeInsets {

    fun bind(dialog: BottomSheetDialog, @Suppress("UNUSED_PARAMETER") paddingTarget: View) {
        val window = dialog.window ?: return
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        WindowCompat.setDecorFitsSystemWindows(window, true)
    }

    fun requestImePadding(target: View) {
        ViewCompat.requestApplyInsets(target)
    }
}
