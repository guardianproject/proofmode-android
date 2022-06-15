package org.witness.proofmode.web

import io.ktor.client.*
import io.ktor.client.engine.android.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import org.witness.proofmode.models.OpenPGPPublicKeyResponse
import org.witness.proofmode.models.OpenPGPUploadBody

object ApiRoutes {
    private const val BASE_URL = "https://keys.openpgp.org/"
   const val  URL_POST_KEY_ENDPOINT = "${BASE_URL}vks/v1/upload"}


/**
 * Used mainly to trigger post requests when publishing the public key
 */
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
                        json(Json{
                            prettyPrint = true
                            isLenient = true
                            ignoreUnknownKeys = true
                        })

                    }
                }
            )
        }

    }
}
