package org.witness.proofmode.storage

data class FilebaseConfig(
    val accessKey: String,
    val secretKey: String,
    val bucketName: String,
    val endpoint: String = "https://s3.filebase.com",
    val enabled: Boolean = false
) {
    
    fun isValid(): Boolean {
        return accessKey.isNotBlank() && 
               secretKey.isNotBlank() && 
               bucketName.isNotBlank() &&
               endpoint.isNotBlank()
    }
    
    companion object {
        const val PREF_FILEBASE_ENABLED = "filebase_enabled"
        const val PREF_FILEBASE_ACCESS_KEY = "filebase_access_key"
        const val PREF_FILEBASE_SECRET_KEY = "filebase_secret_key"
        const val PREF_FILEBASE_BUCKET_NAME = "filebase_bucket_name"
        const val PREF_FILEBASE_ENDPOINT = "filebase_endpoint"
    }
}