package org.witness.proofmode.c2pa.selfsign

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.ContentSigner
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.pkcs.PKCS10CertificationRequest
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder
import org.bouncycastle.util.io.pem.PemObject
import org.bouncycastle.util.io.pem.PemWriter
import org.contentauth.c2pa.C2PAError
import org.contentauth.c2pa.CertificateManager
import org.contentauth.c2pa.CertificateManager.CertificateConfig
import timber.log.Timber
import java.io.File
import java.io.StringWriter
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.security.auth.x500.X500Principal
import kotlin.io.encoding.Base64

class CAWGIdentityManager (private val context: Context) {

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"

    }

    public suspend fun createCawgKey(keyAlias: String, useHardware: Boolean) : String {

        val subject = X500Principal("CN=C2PA Android User, O=C2PA Example, C=US")
        val notBefore = Date()
        val notAfter = Date(System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000)
        val serial = BigInteger.valueOf(System.currentTimeMillis())

        if (useHardware) {
            val keyPairGenerator =
                KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")

            val paramSpec =
                KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
                )
                    .apply {
                        setDigests(KeyProperties.DIGEST_SHA256)
                        setAlgorithmParameterSpec(
                            ECGenParameterSpec("secp256r1"),
                        )

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            setIsStrongBoxBacked(true)
                        }

                        setCertificateSubject(subject)
                        setCertificateSerialNumber(serial)
                        setCertificateNotBefore(notBefore)
                        setCertificateNotAfter(notAfter)
                    }
                    .build()

            keyPairGenerator.initialize(paramSpec)
            keyPairGenerator.generateKeyPair()

            return ""
        } else {
            val keyPairGenerator = KeyPairGenerator.getInstance("EC")
            keyPairGenerator.initialize(ECGenParameterSpec("secp256r1"))
            val keyPair = keyPairGenerator.generateKeyPair()

            // Build self-signed certificate using Bouncy Castle
            val issuer = X500Name("CN=C2PA Android User, O=C2PA Example, C=US")
            val certBuilder = JcaX509v3CertificateBuilder(
                            issuer, serial, notBefore, notAfter, issuer, keyPair.public
                        )
            val signer = JcaContentSignerBuilder("SHA256withECDSA").build(keyPair.private)
            val cert = JcaX509CertificateConverter().getCertificate(certBuilder.build(signer))

            // Write private key PEM
            val privateKeyPem =
                "-----BEGIN PRIVATE KEY-----\n" +
                Base64.encode(keyPair.private.encoded) +
                "\n-----END PRIVATE KEY-----\n"

            var fileKey = File(context.filesDir,"$keyAlias.key")
            fileKey.writeText(privateKeyPem)

            var certChain = getSelfSignedCertChain(keyPair)
            var fileCert = File(context.filesDir,"$keyAlias.cert")
            fileCert.writeText(certChain)

            return privateKeyPem
        }
    }

    private suspend fun getSelfSignedCertChain(keyPair: KeyPair): String {

        // Generate CSR
        val csr = generateCSR(keyPair)

        // Submit CSR to signing server
        val csrResp = CertificateSigningService().signCSR(csr)
        val certChain = csrResp.certificate_chain
        val certId = csrResp.certificate_id

        Timber.d( "Certificate enrolled successfully. ID: $certId")

        return certChain
    }

    private fun generateCSR(keyPair: KeyPair): String {
        try {
            // Use the library's CertificateManager to generate a proper CSR
            val config =
                CertificateManager.CertificateConfig(
                    commonName = "Proofmode C2PA Hardware Key",
                    organization = "Proofmode App Self-Signed",
                    organizationalUnit = "Mobile",
                    country = "US",
                    state = "New York",
                    locality = "New York",
                )

            // Build X500 subject
            val subjectDN = buildX500Name(config)

            // Create CSR using BouncyCastle with the hardware-backed private key
            val csrBuilder = JcaPKCS10CertificationRequestBuilder(subjectDN, keyPair.public)

            // Create content signer using the hardware-backed private key
            val contentSigner = createContentSigner(keyPair.private)

            // Build the CSR
            val csr = csrBuilder.build(contentSigner)

            // Convert to PEM format
            return csrToPEM(csr)

        } catch (e: Exception) {
           // Log.e(TAG, "Failed to generate CSR", e)
            throw RuntimeException("Failed to generate CSR: ${e.message}", e)
        }
    }

    private fun buildX500Name(config: CertificateConfig): X500Name {
        val parts = mutableListOf<String>()
        parts.add("CN=${config.commonName}")
        config.organization?.let { parts.add("O=$it") }
        config.organizationalUnit?.let { parts.add("OU=$it") }
        config.locality?.let { parts.add("L=$it") }
        config.state?.let { parts.add("ST=$it") }
        config.country?.let { parts.add("C=$it") }
        config.emailAddress?.let { parts.add("EMAILADDRESS=$it") }
        return X500Name(parts.joinToString(", "))
    }

    /** Creates a [ContentSigner] using the Android KeyStore for the given private key. */
    private fun createContentSigner(privateKey: PrivateKey): ContentSigner {
        val signatureAlgorithm =
            when (privateKey.algorithm) {
                "EC" -> "SHA256withECDSA"
                "RSA" -> "SHA256withRSA"
                else ->
                    throw C2PAError.Api(
                        "Unsupported key algorithm: ${privateKey.algorithm}",
                    )
            }

        // Create a custom ContentSigner that uses Android KeyStore for signing
        return AndroidKeyStoreContentSigner(privateKey, signatureAlgorithm)
    }

    /** Custom ContentSigner that uses Android KeyStore for signing operations */
    private class AndroidKeyStoreContentSigner(
        private val privateKey: PrivateKey,
        private val signatureAlgorithm: String,
    ) : ContentSigner {

        private val signature = Signature.getInstance(signatureAlgorithm)
        private val outputStream = java.io.ByteArrayOutputStream()

        init {
            signature.initSign(privateKey)
        }

        override fun getAlgorithmIdentifier(): AlgorithmIdentifier = when (signatureAlgorithm) {
            "SHA256withECDSA" ->
                AlgorithmIdentifier(
                    ASN1ObjectIdentifier("1.2.840.10045.4.3.2"), // ecdsaWithSHA256
                )
            "SHA256withRSA" ->
                AlgorithmIdentifier(PKCSObjectIdentifiers.sha256WithRSAEncryption)
            else ->
                throw IllegalArgumentException(
                    "Unsupported algorithm: $signatureAlgorithm",
                )
        }

        override fun getOutputStream(): java.io.OutputStream = outputStream

        override fun getSignature(): ByteArray {
            val dataToSign = outputStream.toByteArray()
            signature.update(dataToSign)
            return signature.sign()
        }
    }

    private fun csrToPEM(csr: PKCS10CertificationRequest): String {
        val writer = StringWriter()
        val pemWriter = PemWriter(writer)
        val pemObject = PemObject("CERTIFICATE REQUEST", csr.encoded)
        pemWriter.writeObject(pemObject)
        pemWriter.close()
        return writer.toString()
    }
}