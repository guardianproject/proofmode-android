package org.witness.proofmode.web

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import org.witness.proofmode.models.OpenPGPPublicKeyResponse
import org.witness.proofmode.models.OpenPGPUploadBody
import timber.log.Timber

class OpenPGPServiceImpl(private val client:HttpClient):OpenPGPService {
    override suspend fun publishPublicKey(keyObject: OpenPGPUploadBody):OpenPGPPublicKeyResponse {
        try {
            val response = client.post(urlString = ApiRoutes.URL_POST_KEY_ENDPOINT){
                headers {
                    append(HttpHeaders.Accept,ContentType.Application.Json)
                }
                contentType(ContentType.Application.Json)
                setBody(keyObject)
            }
            return response.body()
        } catch (ex: Exception) {
            Timber.e("There was an error ${ex.message}")
            ex.printStackTrace()
            throw ex
        }
    }
}