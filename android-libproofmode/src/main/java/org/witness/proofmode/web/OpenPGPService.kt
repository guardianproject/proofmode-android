package org.witness.proofmode.web

import io.ktor.client.*
import io.ktor.client.engine.android.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import org.witness.proofmode.models.OpenPGPPublicKeyResponse
import org.witness.proofmode.models.OpenPGPUploadBody

object ApiRoutes {
    private const val BASE_URL = "https://keys.openpgp.org/"
   const val  URL_POST_KEY_ENDPOINT = "${BASE_URL}v1/upload"}

interface OpenPGPService {

    suspend fun publishPublicKey(keyObject:OpenPGPUploadBody): OpenPGPPublicKeyResponse?

    companion object {

        fun create():OpenPGPService {
            return OpenPGPServiceImpl(
                client = HttpClient(Android) {
                    install(Logging) {
                        level = LogLevel.ALL
                    }
                    install(ContentNegotiation){

                    }
                }
            )
        }

    }
}
