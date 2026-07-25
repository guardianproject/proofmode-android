package org.witness.proofmode.share

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.method.LinkMovementMethod
import android.view.MenuItem
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.HtmlCompat
import androidx.preference.PreferenceManager
import com.google.android.material.switchmaterial.SwitchMaterial
import org.witness.proofmode.R
import org.witness.proofmode.databinding.ActivityFilebaseSettingsBinding
import org.witness.proofmode.service.MediaWatcher
import org.witness.proofmode.storage.filebase.CommitDraftResult
import org.witness.proofmode.storage.filebase.FilebaseConfig
import org.witness.proofmode.storage.filebase.FilebaseSettingsDraft
import org.witness.proofmode.storage.filebase.FilebaseStorageProvider
import org.witness.proofmode.storage.filebase.TestConnectionCallback
import org.witness.proofmode.storage.filebase.hasUsableCredentials

class FilebaseSettingsActivity : AppCompatActivity() {

    private lateinit var baseline: FilebaseConfig
    private lateinit var editAccessKey: EditText
    private lateinit var editSecretKey: EditText
    private lateinit var editBucketName: EditText
    private lateinit var editEndpoint: EditText
    private lateinit var editRegion: EditText
    private lateinit var editIpfsBearerToken: EditText
    private lateinit var switchAutoUpload: SwitchMaterial
    private lateinit var switchAutoIncludeMedia: SwitchMaterial
    private lateinit var buttonTest: Button
    private lateinit var binding: ActivityFilebaseSettingsBinding

    /** Fingerprint of credentials last proven via Test Connection this session. */
    private var validatedCredentialKey: String? = null

    /** After a failed Test Connection, do not treat unchanged baseline prefs as validated. */
    private var distrustBaselineCredentials: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityFilebaseSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        binding.toolbar.setTitleTextColor(getColor(R.color.colorPrimaryDark))
        binding.toolbar.setNavigationIconTint(getColor(R.color.colorPrimaryDark))
        supportActionBar?.title = getString(R.string.auto_sync_title)

        editAccessKey = findViewById(R.id.editAccessKey)
        editSecretKey = findViewById(R.id.editSecretKey)
        editBucketName = findViewById(R.id.editBucketName)
        editEndpoint = findViewById(R.id.editEndpoint)
        editRegion = findViewById(R.id.editRegion)
        editIpfsBearerToken = findViewById(R.id.editIpfsBearerToken)
        switchAutoUpload = findViewById(R.id.switchAutoUpload)
        switchAutoIncludeMedia = findViewById(R.id.switchAutoIncludeMedia)
        buttonTest = findViewById(R.id.buttonTest)

        val instructions = findViewById<TextView>(R.id.textFilebaseInstructions)
        instructions.text =
            HtmlCompat.fromHtml(
                getString(R.string.filebase_instructions_info),
                HtmlCompat.FROM_HTML_MODE_LEGACY,
            )
        instructions.movementMethod = LinkMovementMethod.getInstance()

        baseline = FilebaseConfig.fromPrefs(PreferenceManager.getDefaultSharedPreferences(this))
        loadFrom(baseline)
        wireToggleEnablement()
        buttonTest.setOnClickListener { testConnection() }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = exitWithCommit()
            },
        )
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        when (item.itemId) {
            android.R.id.home -> {
                exitWithCommit()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }

    private fun loadFrom(config: FilebaseConfig) {
        editAccessKey.setText(config.accessKey)
        editSecretKey.setText(config.secretKey)
        editBucketName.setText(config.bucketName)
        editEndpoint.setText(config.endpoint)
        editRegion.setText(config.region)
        editIpfsBearerToken.setText(config.ipfsBearerToken)
        switchAutoUpload.isChecked = config.autoUpload
        switchAutoIncludeMedia.isChecked = config.autoIncludeMedia
    }

    private fun draftFromConfig(config: FilebaseConfig): FilebaseSettingsDraft =
        FilebaseSettingsDraft(
            accessKey = config.accessKey,
            secretKey = config.secretKey,
            bucketName = config.bucketName,
            endpoint = config.endpoint,
            region = config.region,
            ipfsBearerToken = config.ipfsBearerToken,
            autoUpload = config.autoUpload,
            autoIncludeMedia = config.autoIncludeMedia,
        )

    private fun currentDraft(): FilebaseSettingsDraft =
        FilebaseSettingsDraft(
            accessKey = editAccessKey.text.toString().trim(),
            secretKey = editSecretKey.text.toString().trim(),
            bucketName = editBucketName.text.toString().trim(),
            endpoint = editEndpoint.text.toString().trim().ifBlank { "https://s3.filebase.com" },
            region = editRegion.text.toString().trim().ifBlank { "us-east-1" },
            ipfsBearerToken = editIpfsBearerToken.text.toString().trim(),
            autoUpload = switchAutoUpload.isChecked,
            autoIncludeMedia = switchAutoIncludeMedia.isChecked,
        )

    private fun credentialKey(draft: FilebaseSettingsDraft): String =
        listOf(
            draft.accessKey,
            draft.secretKey,
            draft.bucketName,
            draft.endpoint,
            draft.region,
            draft.ipfsBearerToken,
        ).joinToString("\u0000")

    private fun isDraftValidated(): Boolean {
        val key = credentialKey(currentDraft())
        if (validatedCredentialKey == key) return true
        // Unchanged saved configured credentials remain trusted until a failed test.
        return !distrustBaselineCredentials &&
            baseline.isConfigured() &&
            key == credentialKey(draftFromConfig(baseline))
    }

    private fun exitWithCommit() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        var draft = currentDraft()
        // Edits that were never successfully tested must not keep auto-upload on.
        if (!isDraftValidated()) {
            draft = draft.copy(autoUpload = false, autoIncludeMedia = false)
        }
        when (FilebaseConfig.commitDraft(prefs, draft, baseline)) {
            CommitDraftResult.Unchanged -> finish()
            CommitDraftResult.Committed -> {
                MediaWatcher.getInstance(this)?.refreshStorageProvider(null)
                finish()
            }
            CommitDraftResult.DiscardedInvalid -> {
                Toast.makeText(this, R.string.filebase_settings_discarded_invalid, Toast.LENGTH_SHORT)
                    .show()
                MediaWatcher.getInstance(this)?.refreshStorageProvider(null)
                finish()
            }
        }
    }

    private fun wireToggleEnablement() {
        val credentialWatcher =
            object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

                override fun afterTextChanged(s: Editable?) = applyToggleEnablement()
            }

        editAccessKey.addTextChangedListener(credentialWatcher)
        editSecretKey.addTextChangedListener(credentialWatcher)
        editBucketName.addTextChangedListener(credentialWatcher)
        editEndpoint.addTextChangedListener(credentialWatcher)
        editRegion.addTextChangedListener(credentialWatcher)
        editIpfsBearerToken.addTextChangedListener(credentialWatcher)
        switchAutoUpload.setOnCheckedChangeListener { _, _ -> applyToggleEnablement() }
        applyToggleEnablement()
    }

    private fun applyToggleEnablement() {
        val usable = currentDraft().hasUsableCredentials()
        val validated = isDraftValidated()
        buttonTest.isEnabled = usable

        if (!validated) {
            switchAutoUpload.setOnCheckedChangeListener(null)
            switchAutoIncludeMedia.setOnCheckedChangeListener(null)
            switchAutoUpload.isChecked = false
            switchAutoIncludeMedia.isChecked = false
            switchAutoUpload.isEnabled = false
            switchAutoIncludeMedia.isEnabled = false
            switchAutoUpload.alpha = 0.5f
            switchAutoIncludeMedia.alpha = 0.5f
            switchAutoUpload.setOnCheckedChangeListener { _, _ -> applyToggleEnablement() }
        } else {
            switchAutoUpload.isEnabled = true
            switchAutoIncludeMedia.isEnabled = switchAutoUpload.isChecked
            switchAutoUpload.alpha = 1f
            switchAutoIncludeMedia.alpha = if (switchAutoUpload.isChecked) 1f else 0.5f
        }
    }

    private fun testConnection() {
        val draft = currentDraft()
        if (!draft.hasUsableCredentials()) {
            Toast.makeText(this, getString(R.string.please_fill_in_all_required_fields), Toast.LENGTH_SHORT)
                .show()
            return
        }

        val config =
            FilebaseConfig(
                accessKey = draft.accessKey,
                secretKey = draft.secretKey,
                bucketName = draft.bucketName,
                endpoint = draft.endpoint,
                region = draft.region,
                enabled = true,
                ipfsBearerToken = draft.ipfsBearerToken,
            )

        val tokenPresent = config.ipfsBearerToken.isNotBlank()

        buttonTest.isEnabled = false
        buttonTest.text = "Testing..."

        try {
            val filebaseProvider =
                FilebaseStorageProvider(
                    accessKey = if (tokenPresent) "" else config.accessKey,
                    secretKey = if (tokenPresent) "" else config.secretKey,
                    bucketName = if (tokenPresent) "" else config.bucketName,
                    endpoint = config.endpoint,
                    region = config.region,
                    ipfsBearerToken = config.ipfsBearerToken,
                )

            val callback =
                object : TestConnectionCallback {
                    override fun onTestSuccess() {
                        runOnUiThread {
                            buttonTest.isEnabled = true
                            buttonTest.text = getString(R.string.filebase_test_connection)
                            val alreadyValidated = isDraftValidated()
                            validatedCredentialKey = credentialKey(currentDraft())
                            distrustBaselineCredentials = false
                            if (!alreadyValidated) {
                                switchAutoUpload.setOnCheckedChangeListener(null)
                                switchAutoUpload.isChecked = true
                                switchAutoIncludeMedia.isChecked = true
                                switchAutoUpload.setOnCheckedChangeListener { _, _ ->
                                    applyToggleEnablement()
                                }
                            }
                            applyToggleEnablement()
                            val message =
                                if (tokenPresent) {
                                    getString(R.string.filebase_test_ipfs)
                                } else {
                                    "Connection test successful!"
                                }
                            Toast.makeText(
                                this@FilebaseSettingsActivity,
                                message,
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }

                    override fun onTestFailure(error: String) {
                        runOnUiThread {
                            buttonTest.isEnabled = true
                            buttonTest.text = getString(R.string.filebase_test_connection)
                            validatedCredentialKey = null
                            distrustBaselineCredentials = true
                            applyToggleEnablement()
                            val prefix =
                                if (tokenPresent) {
                                    "IPFS connection test failed"
                                } else {
                                    "Connection test failed"
                                }
                            Toast.makeText(
                                this@FilebaseSettingsActivity,
                                "$prefix: $error",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                }

            when {
                tokenPresent -> filebaseProvider.testIpfsConnection(callback)
                config.hasS3Access() -> filebaseProvider.testConnection(callback)
                else -> {
                    buttonTest.isEnabled = true
                    buttonTest.text = getString(R.string.filebase_test_connection)
                    Toast.makeText(
                        this,
                        getString(R.string.please_fill_in_all_required_fields),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            buttonTest.isEnabled = true
            buttonTest.text = getString(R.string.filebase_test_connection)
            Toast.makeText(this, "Error creating test connection: ${e.message}", Toast.LENGTH_LONG)
                .show()
        }
    }
}
