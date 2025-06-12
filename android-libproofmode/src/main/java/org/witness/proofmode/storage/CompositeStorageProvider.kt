package org.witness.proofmode.storage

import android.net.Uri
import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.util.ArrayList

class CompositeStorageProvider(
    private val primaryProvider: StorageProvider,
    private val secondaryProvider: StorageProvider? = null
) : StorageProvider {

    companion object {
        private const val TAG = "CompositeStorageProvider"
    }

    override fun saveStream(hash: String, identifier: String, stream: InputStream, listener: StorageListener?) {
        // Save to primary provider first
        primaryProvider.saveStream(hash, identifier, stream, listener)
        
        // Save to secondary provider if available (non-blocking)
        secondaryProvider?.let { secondary ->
            try {
                // Reset stream if possible, otherwise secondary will get empty stream
                if (stream.markSupported()) {
                    stream.reset()
                } else {
                    Log.w(TAG, "Stream doesn't support reset, secondary provider may get empty stream")
                }
                
                secondary.saveStream(hash, identifier, stream, object : StorageListener {
                    override fun saveSuccessful(hash: String?, uri: String?) {
                        Log.d(TAG, "Successfully saved $identifier to secondary storage at: $uri")
                        primaryProvider.saveText(hash, "$identifier.uri", uri, null)
                    }
                    
                    override fun saveFailed(exception: Exception?) {
                        Log.w(TAG, "Failed to save $identifier to secondary storage: ${exception?.message}")
                    }
                })
            } catch (e: Exception) {
                Log.e(TAG, "Error saving to secondary provider", e)
            }
        }
    }

    override fun saveBytes(hash: String, identifier: String, data: ByteArray, listener: StorageListener?) {
        // Save to primary provider first
        primaryProvider.saveBytes(hash, identifier, data, listener)
        
        // Save to secondary provider if available (non-blocking)
        secondaryProvider?.saveBytes(hash, identifier, data, object : StorageListener {
            override fun saveSuccessful(hash: String?, uri: String?) {
                Log.d(TAG, "Successfully saved $identifier to secondary storage at: $uri")
                primaryProvider.saveText(hash, "$identifier.uri", uri, null)
            }
            
            override fun saveFailed(exception: Exception?) {
                Log.w(TAG, "Failed to save $identifier to secondary storage: ${exception?.message}")
            }
        })
    }

    override fun saveText(hash: String, identifier: String, data: String, listener: StorageListener?) {
        // Save to primary provider first
        primaryProvider.saveText(hash, identifier, data, listener)
        
        // Save to secondary provider if available (non-blocking)
        secondaryProvider?.saveText(hash, identifier, data, object : StorageListener {
            override fun saveSuccessful(hash: String?, uri: String?) {
                Log.d(TAG, "Successfully saved text $identifier to secondary storage at: $uri")
                primaryProvider.saveText(hash, "$identifier.uri", uri, null)
            }
            
            override fun saveFailed(exception: Exception?) {
                Log.w(TAG, "Failed to save text $identifier to secondary storage: ${exception?.message}")
            }
        })
    }

    // All read operations delegate to primary provider only
    override fun getInputStream(hash: String, identifier: String): InputStream? {
        return primaryProvider.getInputStream(hash, identifier)
    }

    override fun proofExists(hash: String): Boolean {
        return primaryProvider.proofExists(hash)
    }

    override fun proofIdentifierExists(hash: String, identifier: String): Boolean {
        return primaryProvider.proofIdentifierExists(hash, identifier)
    }

    override fun getProofSet(hash: String): ArrayList<Uri> {
        return primaryProvider.getProofSet(hash)
    }

    override fun getProofItem(uri: Uri): InputStream? {
        return primaryProvider.getProofItem(uri)
    }
}