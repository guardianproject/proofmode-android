package org.witness.proofmode.web

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import org.witness.proofmode.models.OpenPGPPublicKeyResponse
import org.witness.proofmode.models.OpenPGPUploadBody

class OpenPGPServiceImpl(private val client:HttpClient):OpenPGPService {
    override suspend fun publishPublicKey(keyObject: OpenPGPUploadBody):OpenPGPPublicKeyResponse? {
        try {
            return client.post(urlString = ApiRoutes.URL_POST_KEY_ENDPOINT){
                contentType(ContentType.Application.Json)
                setBody(keyObject)
            }.body()
            //return response.body()
        } catch (ex: Exception) {
            throw ex
        }
    }
}