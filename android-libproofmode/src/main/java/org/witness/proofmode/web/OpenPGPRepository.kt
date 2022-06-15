package org.witness.proofmode.web

import io.ktor.client.plugins.*
import org.witness.proofmode.models.OpenPGPPublicKeyResponse
import org.witness.proofmode.models.OpenPGPUploadBody

class OpenPGPRepository {
    val service: OpenPGPService by lazy {
        OpenPGPService.create()
    }

    suspend fun publishPublicKey(keyObject: OpenPGPUploadBody): Result<OpenPGPPublicKeyResponse?> {
        return try {
            val result = service.publishPublicKey(keyObject)
            Result.success(result)
        }catch (ex:ClientRequestException) {
            Result.failure(exception = Exception(ex.message))
        } catch (servEx:ServerResponseException) {
            Result.failure(exception = Exception(servEx.message))
        } catch (ex:Exception) {
            Result.failure(ex)
        }
    }
}