package org.witness.proofmode

import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class PermissionActivity : AppCompatActivity() {
    private var uiMode = true
    private var showMissingPermissionsDialog = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        val layoutId = intent.getIntExtra(ARG_LAYOUT_ID, 0)
        val buttonContinueId = intent.getIntExtra(ARG_BUTTON_CONTINUE_ID, R.id.btnContinue)
        val buttonCloseId = intent.getIntExtra(ARG_BUTTON_CLOSE_ID, R.id.btnClose)
        if (layoutId != 0 && buttonContinueId != 0 && buttonCloseId != 0) {
            setContentView(layoutId)
            val btn = findViewById<Button>(buttonContinueId)
            btn?.setOnClickListener { requestPermissions() }

            //Close
            val btnClose = findViewById<ImageButton>(buttonCloseId)
            btnClose?.setOnClickListener { finish() }
        } else {
            // No UI mode, just ask
            uiMode = false
            requestPermissions()
        }
    }

    override fun onResume() {
        super.onResume()
        if (showMissingPermissionsDialog) {
            showMissingPermissionsDialog = false
            /**
             * new AlertDialog.Builder(this)
             * .setTitle(R.string.permissions_needed_title)
             * .setMessage(R.string.permissions_needed_content)
             * .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
             * @Override
             * public void onClick(DialogInterface dialog, int which) {
             * finish();
             * }
             * }).show();
             */
            finish()
        }
    }

    private fun requestPermissions() {
        val permissions = intent.getStringArrayExtra(ARG_PERMISSIONS)
        val missingPermissions = missingPermissions(this, permissions)
        if (missingPermissions == null) {
            finish()
        } else {
            ActivityCompat.requestPermissions(this, missingPermissions, 1)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val wantedPermissions = intent.getStringArrayExtra(ARG_PERMISSIONS)
        val missingPermissions = missingPermissions(this, wantedPermissions)
        if (missingPermissions == null) {
            finish() // Done!
        } else {
            for (p in missingPermissions) {
                // Should we show an explanation?
                if (!ActivityCompat.shouldShowRequestPermissionRationale(this, p)) {
                    showMissingPermissionsDialog = true
                    return
                }
            }
            if (!uiMode) {
                finish()
            }
        }
    }

    companion object {
        const val ARG_PERMISSIONS = "permissions"
        const val ARG_LAYOUT_ID = "layout_id"
        const val ARG_BUTTON_CONTINUE_ID = "button_continue_id"
        const val ARG_BUTTON_CLOSE_ID = "button_close_id"

        /**
         * Given a set of permissions, return those that we don't have permission for.
         * @param permissions
         * @return
         */
        private fun missingPermissions(
            context: Context,
            permissions: Array<String>?
        ): Array<String>? {
            // The permission check for sdk 18 removed as it is unnecessary
            val missingPermissions = ArrayList<String>()
            permissions?.let { perms ->
                for (permission in perms) {
                    val permissionCheck = ContextCompat.checkSelfPermission(context, permission)
                    if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
                        missingPermissions.add(permission)
                    }
                }
            }
            return if (missingPermissions.size > 0) {
                missingPermissions.toTypedArray()
            } else null
        }

        fun hasPermissions(context: Context, permissions: Array<String>?): Boolean {
            val missingPermissions = missingPermissions(context, permissions)
            return missingPermissions == null
        }
    }
}