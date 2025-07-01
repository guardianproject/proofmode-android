package org.witness.proofmode.storage

import android.content.Context
import android.content.SharedPreferences
import android.preference.PreferenceManager
import android.util.Log

class StorageProviderManager private constructor() {
    
    companion object {
        private const val TAG = "StorageProviderManager"
        
        @Volatile
        private var INSTANCE: StorageProviderManager? = null
        
        fun getInstance(): StorageProviderManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: StorageProviderManager().also { INSTANCE = it }
            }
        }
    }
    
    private var primaryStorageProvider: StorageProvider? = null
    private var filebaseStorageProvider: FilebaseStorageProvider? = null
    
    fun initializeStorageProviders(context: Context) {
        // Always use DefaultStorageProvider as primary
        primaryStorageProvider = DefaultStorageProvider(context)
        
        // Initialize Filebase if configured
        initializeFilebaseProvider(context)
    }
    
    private fun initializeFilebaseProvider(context: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val filebaseConfig = getFilebaseConfig(prefs)
        
        if (filebaseConfig.enabled && filebaseConfig.isValid()) {
            try {
                filebaseStorageProvider = FilebaseStorageProvider(
                    accessKey = filebaseConfig.accessKey,
                    secretKey = filebaseConfig.secretKey,
                    bucketName = filebaseConfig.bucketName,
                    endpoint = filebaseConfig.endpoint
                )
                Log.i(TAG, "Filebase storage provider initialized")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize Filebase storage provider", e)
                filebaseStorageProvider = null
            }
        } else {
            filebaseStorageProvider = null
            Log.d(TAG, "Filebase storage provider not configured or disabled")
        }
    }
    
    private fun getFilebaseConfig(prefs: SharedPreferences): FilebaseConfig {
        return FilebaseConfig(
            enabled = prefs.getBoolean(FilebaseConfig.PREF_FILEBASE_ENABLED, false),
            accessKey = prefs.getString(FilebaseConfig.PREF_FILEBASE_ACCESS_KEY, "") ?: "",
            secretKey = prefs.getString(FilebaseConfig.PREF_FILEBASE_SECRET_KEY, "") ?: "",
            bucketName = prefs.getString(FilebaseConfig.PREF_FILEBASE_BUCKET_NAME, "") ?: "",
            endpoint = prefs.getString(FilebaseConfig.PREF_FILEBASE_ENDPOINT, "https://s3.filebase.com") ?: "https://s3.filebase.com"
        )
    }
    
    fun getPrimaryStorageProvider(): StorageProvider? {
        return primaryStorageProvider
    }
    
    fun saveToAllProviders(hash: String, identifier: String, data: ByteArray, listener: StorageListener?) {
        // Save to primary storage first
        primaryStorageProvider?.saveBytes(hash, identifier, data, listener)
        
        // Also save to Filebase if available
        filebaseStorageProvider?.saveBytes(hash, identifier, data, object : StorageListener {
            override fun saveSuccessful(hash: String?, uri: String?) {
                Log.d(TAG, "Successfully uploaded $identifier to Filebase")
            }
            
            override fun saveFailed(exception: Exception?) {
                Log.w(TAG, "Failed to upload $identifier to Filebase: ${exception?.message}")
            }
        })
    }
    
    fun saveTextToAllProviders(hash: String, identifier: String, data: String, listener: StorageListener?) {
        // Save to primary storage first
        primaryStorageProvider?.saveText(hash, identifier, data, listener)
        
        // Also save to Filebase if available
        filebaseStorageProvider?.saveText(hash, identifier, data, object : StorageListener {
            override fun saveSuccessful(hash: String?, uri: String?) {
                Log.d(TAG, "Successfully uploaded text $identifier to Filebase")
            }
            
            override fun saveFailed(exception: Exception?) {
                Log.w(TAG, "Failed to upload text $identifier to Filebase: ${exception?.message}")
            }
        })
    }
    
    fun refreshFilebaseConfiguration(context: Context) {
        initializeFilebaseProvider(context)
    }
    
    fun isFilebaseEnabled(): Boolean {
        return filebaseStorageProvider != null
    }

    fun getFilebaseProvider (): FilebaseStorageProvider? {
        return filebaseStorageProvider
    }
}
