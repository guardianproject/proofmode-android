package org.witness.proofmode.c2pa.tsa

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.util.io.pem.PemObject
import org.bouncycastle.util.io.pem.PemReader
import org.bouncycastle.util.io.pem.PemWriter
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.StringReader
import java.io.StringWriter
import java.math.BigInteger
import java.net.InetAddress
import java.net.URI
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.security.auth.x500.X500Principal

/**
 * The on-device Time Stamp Authority behind a loopback `tsaServerUrl`.
 *
 * When the configured TSA URL names a loopback host (`localhost`, `127.0.0.1`,
 * `::1`), [resolve] starts a [LocalTsaServer] on that port and hands c2pa-rs the
 * server's URL instead of a remote TSA. Timestamps are then signed by a
 * non-exportable P-256 key in the Android Keystore with a self-signed
 * id-kp-timeStamping certificate, persisted so every token from this install
 * chains to the same certificate.
 *
 * The time asserted is the device clock, so these tokens prove nothing a remote
 * TSA would — they let signing work offline and give the manifest a
 * consistently-formed RFC 3161 countersignature. Validators will report the
 * TSA as untrusted unless its certificate is added to their TSA trust list.
 */
object LocalTsa {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "C2PA_LOCAL_TSA_KEY"
    private const val CERT_VALIDITY_DAYS = 3650L

    /** Port used when a loopback TSA URL doesn't name one; apps can't bind below 1024. */
    const val DEFAULT_PORT = 3161
    const val DEFAULT_URL = "http://127.0.0.1:$DEFAULT_PORT/"

    private val IPV4_LITERAL = Regex("""\d{1,3}(\.\d{1,3}){3}""")

    private var server: LocalTsaServer? = null

    /**
     * Returns the URL c2pa-rs should use for [configuredUrl]: unchanged for a
     * remote TSA, or the running local server's URL for a loopback one. If the
     * port is already taken by another process (e.g. an `adb reverse` to a desktop
     * TSA) the configured URL is passed through so that process answers instead.
     */
    @Synchronized
    fun resolve(context: Context, configuredUrl: String): String {
        val uri = try {
            URI(configuredUrl.trim())
        } catch (e: Exception) {
            return configuredUrl
        }
        if (!uri.scheme.equals("http", ignoreCase = true) || !isLoopback(uri.host)) return configuredUrl

        val port = if (uri.port > 0) uri.port else DEFAULT_PORT
        server?.let {
            if (it.isRunning && it.port == port) return it.url
            it.close()
        }
        server = null

        return try {
            val (key, cert) = loadOrCreateIdentity(context.applicationContext)
            LocalTsaServer(TimeStampResponder(key, cert), port).also { server = it }.url
        } catch (e: IOException) {
            Timber.w(e, "LocalTsa: could not bind port %d, using %s as configured", port, configuredUrl)
            configuredUrl
        }
    }

    /** PEM of the TSA certificate, for adding to a validator's TSA trust list; null until first use. */
    fun certificatePem(context: Context): String? =
        certFile(context).takeIf { it.exists() }?.readText()

    @Synchronized
    fun stop() {
        server?.close()
        server = null
    }

    private fun isLoopback(host: String?): Boolean {
        val h = host?.removePrefix("[")?.removeSuffix("]") ?: return false
        if (h.equals("localhost", ignoreCase = true)) return true
        // Only literal addresses: never let a hostname trigger a DNS lookup here.
        if (!IPV4_LITERAL.matches(h) && ':' !in h) return false
        return try {
            InetAddress.getByName(h).isLoopbackAddress
        } catch (e: Exception) {
            false
        }
    }

    private fun certFile(context: Context) = File(context.filesDir, "$KEY_ALIAS.cert")

    private fun loadOrCreateIdentity(context: Context): Pair<PrivateKey, X509Certificate> {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE).apply {
                initialize(
                    KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                        .setCertificateSubject(X500Principal("CN=ProofMode Local TSA"))
                        .build(),
                )
            }.generateKeyPair()
            certFile(context).delete() // belonged to a previous key, if any
        }

        val privateKey = keyStore.getKey(KEY_ALIAS, null) as PrivateKey
        val file = certFile(context)
        val stored = if (file.exists()) parseCertificate(file.readText()) else null
        // Certs from before the AKI fix fail c2pa-rs's profile check; reissue them.
        val cert = if (stored != null && stored.getExtensionValue(Extension.authorityKeyIdentifier.id) != null) {
            stored
        } else {
            createTsaCertificate(privateKey, keyStore.getCertificate(KEY_ALIAS).publicKey).also {
                file.writeText(toPem(it))
            }
        }
        return privateKey to cert
    }

    /**
     * The Keystore's own self-signed certificate lacks the critical
     * id-kp-timeStamping EKU that RFC 3161 §2.3 requires (and BouncyCastle's token
     * generator enforces), so build a replacement, self-signed by the same key.
     *
     * The AuthorityKeyIdentifier is redundant on a self-signed cert but mandatory:
     * c2pa-rs runs the TSA cert through the C2PA certificate profile
     * (`check_certificate_profile`), which requires one, and reports its absence as
     * `signingCredential.invalid` — failing the whole manifest, not just the timestamp.
     */
    internal fun createTsaCertificate(privateKey: PrivateKey, publicKey: PublicKey): X509Certificate {
        val subject = X500Name("CN=ProofMode Local TSA, O=ProofMode, OU=On-device Time Stamp Authority")
        val now = System.currentTimeMillis()
        val builder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger.valueOf(now),
            Date(now - 5 * 60_000L),
            Date(now + CERT_VALIDITY_DAYS * 24 * 60 * 60 * 1000),
            subject,
            publicKey,
        )
        val extUtils = JcaX509ExtensionUtils()
        builder.addExtension(Extension.subjectKeyIdentifier, false, extUtils.createSubjectKeyIdentifier(publicKey))
        builder.addExtension(Extension.authorityKeyIdentifier, false, extUtils.createAuthorityKeyIdentifier(publicKey))
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        builder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature))
        builder.addExtension(Extension.extendedKeyUsage, true, ExtendedKeyUsage(KeyPurposeId.id_kp_timeStamping))

        val signer = JcaContentSignerBuilder("SHA256withECDSA").build(privateKey)
        return JcaX509CertificateConverter().getCertificate(builder.build(signer))
    }

    private fun parseCertificate(pem: String): X509Certificate {
        val der = PemReader(StringReader(pem)).use { it.readPemObject().content }
        return CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate
    }

    private fun toPem(cert: X509Certificate): String =
        StringWriter().also { w -> PemWriter(w).use { it.writeObject(PemObject("CERTIFICATE", cert.encoded)) } }.toString()
}
