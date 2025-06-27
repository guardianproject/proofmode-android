package org.witness.proofmode.org.witness.proofmode.share

import android.os.Bundle
import android.view.MenuItem
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import org.witness.proofmode.R
import org.witness.proofmode.storage.FilebaseConfig

class FilebaseSettingsActivity : AppCompatActivity() {

    private lateinit var switchFilebaseEnabled: CheckBox
    private lateinit var editAccessKey: EditText
    private lateinit var editSecretKey: EditText
    private lateinit var editBucketName: EditText
    private lateinit var editEndpoint: EditText
    private lateinit var buttonSave: Button
    private lateinit var buttonTest: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_filebase_settings)

        // Setup toolbar
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        supportActionBar?.title = "Filebase Configuration"

        // Initialize views
        switchFilebaseEnabled = findViewById(R.id.switchFilebaseEnabled)
        editAccessKey = findViewById(R.id.editAccessKey)
        editSecretKey = findViewById(R.id.editSecretKey)
        editBucketName = findViewById(R.id.editBucketName)
        editEndpoint = findViewById(R.id.editEndpoint)
        buttonSave = findViewById(R.id.buttonSave)
        buttonTest = findViewById(R.id.buttonTest)

        // Load existing settings
        loadSettings()

        // Setup listeners
        buttonSave.setOnClickListener { saveSettings() }
        buttonTest.setOnClickListener { testConnection() }
        
        switchFilebaseEnabled.setOnCheckedChangeListener { _, isChecked ->
            enableConfigFields(isChecked)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun loadSettings() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        
        switchFilebaseEnabled.isChecked = prefs.getBoolean(FilebaseConfig.PREF_FILEBASE_ENABLED, false)
        editAccessKey.setText(prefs.getString(FilebaseConfig.PREF_FILEBASE_ACCESS_KEY, ""))
        editSecretKey.setText(prefs.getString(FilebaseConfig.PREF_FILEBASE_SECRET_KEY, ""))
        editBucketName.setText(prefs.getString(FilebaseConfig.PREF_FILEBASE_BUCKET_NAME, ""))
        editEndpoint.setText(prefs.getString(FilebaseConfig.PREF_FILEBASE_ENDPOINT, "https://s3.filebase.com"))
        
        enableConfigFields(switchFilebaseEnabled.isChecked)
    }

    private fun enableConfigFields(enabled: Boolean) {
        editAccessKey.isEnabled = enabled
        editSecretKey.isEnabled = enabled
        editBucketName.isEnabled = enabled
        editEndpoint.isEnabled = enabled
        buttonTest.isEnabled = enabled
    }

    private fun saveSettings() {
        val config = FilebaseConfig(
            accessKey = editAccessKey.text.toString().trim(),
            secretKey = editSecretKey.text.toString().trim(),
            bucketName = editBucketName.text.toString().trim(),
            endpoint = editEndpoint.text.toString().trim(),
            enabled = switchFilebaseEnabled.isChecked
        )

        if (config.enabled && !config.isValid()) {
            Toast.makeText(this, getString(R.string.please_fill_in_all_required_fields), Toast.LENGTH_SHORT).show()
            return
        }

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        with(prefs.edit()) {
            putBoolean(FilebaseConfig.PREF_FILEBASE_ENABLED, config.enabled)
            putString(FilebaseConfig.PREF_FILEBASE_ACCESS_KEY, config.accessKey)
            putString(FilebaseConfig.PREF_FILEBASE_SECRET_KEY, config.secretKey)
            putString(FilebaseConfig.PREF_FILEBASE_BUCKET_NAME, config.bucketName)
            putString(FilebaseConfig.PREF_FILEBASE_ENDPOINT, config.endpoint)
            apply()
        }

        //val show = Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()

        finish()
    }

    private fun testConnection() {
        val config = FilebaseConfig(
            accessKey = editAccessKey.text.toString().trim(),
            secretKey = editSecretKey.text.toString().trim(),
            bucketName = editBucketName.text.toString().trim(),
            endpoint = editEndpoint.text.toString().trim(),
            enabled = true
        )

        if (!config.isValid()) {
            Toast.makeText(this,
                getString(R.string.please_fill_in_all_required_fields), Toast.LENGTH_SHORT).show()
            return
        }
        else
        {
            Toast.makeText(this,"All good!" ,Toast.LENGTH_SHORT).show()
        }

        // TODO: Implement test upload to verify credentials
        //Toast.makeText(this, "Connection test not yet implemented", Toast.LENGTH_SHORT).show()
    }
}