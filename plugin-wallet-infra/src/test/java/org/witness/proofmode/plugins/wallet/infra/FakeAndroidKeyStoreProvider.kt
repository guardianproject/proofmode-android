package org.witness.proofmode.plugins.wallet.infra

import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStore
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.Date
import java.util.Enumeration
import javax.crypto.KeyGenerator
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey

/**
 * Registers an in-memory AndroidKeyStore provider so Robolectric unit tests can use
 * EncryptedSharedPreferences without a device keystore.
 */
object FakeAndroidKeyStoreProvider {
    private var installed = false

    fun setup() {
        if (installed || Security.getProvider("AndroidKeyStore") != null) {
            installed = true
            return
        }
        Security.addProvider(
            object : Provider("AndroidKeyStore", 1.0, "Fake AndroidKeyStore for unit tests") {
                init {
                    put("KeyStore.AndroidKeyStore", FakeKeyStore::class.java.name)
                    put("KeyGenerator.AES", FakeAesKeyGenerator::class.java.name)
                }
            },
        )
        installed = true
    }
}

class FakeKeyStore : KeyStoreSpi() {
    private val wrapped: KeyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null) }

    override fun engineGetKey(alias: String?, password: CharArray?): Key? =
        wrapped.getKey(alias, password)

    override fun engineGetCertificateChain(alias: String?): Array<Certificate>? =
        wrapped.getCertificateChain(alias)

    override fun engineGetCertificate(alias: String?): Certificate? =
        wrapped.getCertificate(alias)

    override fun engineGetCreationDate(alias: String?): Date? =
        wrapped.getCreationDate(alias)

    override fun engineSetKeyEntry(
        alias: String?,
        key: Key?,
        password: CharArray?,
        chain: Array<out Certificate>?,
    ) {
        wrapped.setKeyEntry(alias, key, password, chain)
    }

    override fun engineSetKeyEntry(alias: String?, key: ByteArray?, chain: Array<out Certificate>?) {
        wrapped.setKeyEntry(alias, key, chain)
    }

    override fun engineSetCertificateEntry(alias: String?, cert: Certificate?) {
        wrapped.setCertificateEntry(alias, cert)
    }

    override fun engineDeleteEntry(alias: String?) {
        wrapped.deleteEntry(alias)
    }

    override fun engineAliases(): Enumeration<String> = wrapped.aliases()

    override fun engineContainsAlias(alias: String?): Boolean = wrapped.containsAlias(alias)

    override fun engineSize(): Int = wrapped.size()

    override fun engineIsKeyEntry(alias: String?): Boolean = wrapped.isKeyEntry(alias)

    override fun engineIsCertificateEntry(alias: String?): Boolean = wrapped.isCertificateEntry(alias)

    override fun engineGetCertificateAlias(cert: Certificate?): String? =
        wrapped.getCertificateAlias(cert)

    override fun engineStore(stream: OutputStream?, password: CharArray?) {
        wrapped.store(stream, password)
    }

    override fun engineLoad(stream: InputStream?, password: CharArray?) {
        wrapped.load(stream, password)
    }

    override fun engineProbe(stream: InputStream?): Boolean = false

    override fun engineEntryInstanceOf(alias: String?, type: Class<out KeyStore.Entry>): Boolean =
        wrapped.entryInstanceOf(alias, type)
}

class FakeAesKeyGenerator : KeyGeneratorSpi() {
    private val wrapped = KeyGenerator.getInstance("AES")

    override fun engineInit(random: SecureRandom?) = Unit

    override fun engineInit(params: AlgorithmParameterSpec?, random: SecureRandom?) = Unit

    override fun engineInit(keysize: Int, random: SecureRandom?) = Unit

    override fun engineGenerateKey(): SecretKey = wrapped.generateKey()
}
