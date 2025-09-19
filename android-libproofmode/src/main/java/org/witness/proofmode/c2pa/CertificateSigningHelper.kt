package org.witness.proofmode.c2pa

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.AuthorityKeyIdentifier
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.asn1.x509.SubjectKeyIdentifier
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.util.io.pem.PemObject
import org.bouncycastle.util.io.pem.PemReader
import org.bouncycastle.util.io.pem.PemWriter
import java.io.StringReader
import java.io.StringWriter
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Security
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.util.Date

data class CertificateOptions(
    val certificateType: CertificateType,
    val organizationName: String = "Test Organization",
    val validityDays: Int = when (certificateType) {
        CertificateType.ROOT -> 3650  // 10 years
        CertificateType.INTERMEDIATE -> 1825  // 5 years
        CertificateType.CONTENT_CREDENTIALS -> 365  // 1 year
    },
    val email: String? = null,
    val pgpFingerprint: String? = null
)

enum class CertificateType {
    ROOT,
    INTERMEDIATE, 
    CONTENT_CREDENTIALS
}

data class Certificate(
    val certificate: X509Certificate,
    val keyPair: KeyPair,
    val pemCertificate: String,
    val pemPrivateKey: String
)

object CertificateSigningHelper {
    
    init {
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.insertProviderAt(BouncyCastleProvider(), 1)
    }
    
    private val secureRandom = SecureRandom()
    
    fun createPrivateKey(): KeyPair {
        val keyPairGenerator = KeyPairGenerator.getInstance("EC", BouncyCastleProvider.PROVIDER_NAME)
        keyPairGenerator.initialize(256, secureRandom)
        return keyPairGenerator.generateKeyPair()
    }
    
    fun createRootCertificate(options: CertificateOptions = CertificateOptions(CertificateType.ROOT)): Certificate {
        val keyPair = createPrivateKey()
        val subject = createSubjectDN(options)
        
        val notBefore = Date()
        val notAfter = Date(System.currentTimeMillis() + options.validityDays * 24L * 60L * 60L * 1000L)
        val serialNumber = BigInteger(64, secureRandom)
        
        val certificateBuilder = JcaX509v3CertificateBuilder(
            subject,
            serialNumber,
            notBefore,
            notAfter,
            subject, // Self-signed
            keyPair.public
        )
        
        // Add extensions for root certificate
        certificateBuilder.addExtension(
            Extension.basicConstraints,
            true,
            BasicConstraints(true) // CA certificate
        )
        
        certificateBuilder.addExtension(
            Extension.keyUsage,
            true,
            KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign or KeyUsage.digitalSignature)
        )
        
        // Add subject key identifier
        certificateBuilder.addExtension(
            Extension.subjectKeyIdentifier,
            false,
            createSubjectKeyIdentifier(keyPair)
        )
        
        val signer = JcaContentSignerBuilder("SHA256withECDSA").setProvider("BC").build(keyPair.private)
        val certificate = JcaX509CertificateConverter().setProvider("BC").getCertificate(certificateBuilder.build(signer))
        
        return Certificate(
            certificate = certificate,
            keyPair = keyPair,
            pemCertificate = toPEM(certificate),
            pemPrivateKey = toPEM(keyPair.private)
        )
    }
    
    fun createContentCredentialsCertificate(
        issuer: Certificate,
        options: CertificateOptions = CertificateOptions(CertificateType.CONTENT_CREDENTIALS)
    ): Certificate {
        val keyPair = createPrivateKey()
        val subject = createSubjectDN(options)
        
        val notBefore = Date()
        val notAfter = Date(System.currentTimeMillis() + options.validityDays * 24L * 60L * 60L * 1000L)
        val serialNumber = BigInteger(64, secureRandom)
        
        val issuerName = X500Name(issuer.certificate.subjectX500Principal.name)
        
        val certificateBuilder = JcaX509v3CertificateBuilder(
            issuerName,
            serialNumber,
            notBefore,
            notAfter,
            subject,
            keyPair.public
        )
        
        // Add extensions for content credentials certificate
        certificateBuilder.addExtension(
            Extension.basicConstraints,
            true,
            BasicConstraints(false) // End entity certificate
        )
        
        certificateBuilder.addExtension(
            Extension.keyUsage,
            true,
            KeyUsage(KeyUsage.digitalSignature)
        )
        
        // Add subject key identifier
        certificateBuilder.addExtension(
            Extension.subjectKeyIdentifier,
            false,
            createSubjectKeyIdentifier(keyPair)
        )
        
        // Add authority key identifier
        certificateBuilder.addExtension(
            Extension.authorityKeyIdentifier,
            false,
            createAuthorityKeyIdentifier(issuer.keyPair)
        )
        
        val signer = JcaContentSignerBuilder("SHA256withECDSA").setProvider("BC").build(issuer.keyPair.private)
        val certificate = JcaX509CertificateConverter().setProvider("BC").getCertificate(certificateBuilder.build(signer))
        
        return Certificate(
            certificate = certificate,
            keyPair = keyPair,
            pemCertificate = toPEM(certificate),
            pemPrivateKey = toPEM(keyPair.private)
        )
    }
    
    private fun createSubjectDN(options: CertificateOptions): X500Name {
        val components = mutableListOf<String>()
        
        when (options.certificateType) {
            CertificateType.ROOT -> {
                components.add("CN=Root CA")
                components.add("O=${options.organizationName}")
            }
            CertificateType.INTERMEDIATE -> {
                components.add("CN=Intermediate CA")
                components.add("O=${options.organizationName}")
            }
            CertificateType.CONTENT_CREDENTIALS -> {
                components.add("CN=Content Credentials")
                components.add("O=${options.organizationName}")
            }
        }
        
        options.email?.let { components.add("emailAddress=$it") }
        
        return X500Name(components.joinToString(", "))
    }
    
    private fun createSubjectKeyIdentifier(keyPair: KeyPair): SubjectKeyIdentifier {
        val ecPublicKey = keyPair.public as ECPublicKey
        val encodedPoint = ecPublicKey.w.affineX.toByteArray() + ecPublicKey.w.affineY.toByteArray()
        return SubjectKeyIdentifier(encodedPoint)
    }
    
    private fun createAuthorityKeyIdentifier(issuerKeyPair: KeyPair): AuthorityKeyIdentifier {
        val ecPublicKey = issuerKeyPair.public as ECPublicKey
        val encodedPoint = ecPublicKey.w.affineX.toByteArray() + ecPublicKey.w.affineY.toByteArray()
        return AuthorityKeyIdentifier(encodedPoint)
    }
    
    private fun toPEM(certificate: X509Certificate): String {
        val stringWriter = StringWriter()
        val pemWriter = PemWriter(stringWriter)
        pemWriter.writeObject(PemObject("CERTIFICATE", certificate.encoded))
        pemWriter.close()
        return stringWriter.toString()
    }
    
    private fun toPEM(privateKey: java.security.PrivateKey): String {
        val stringWriter = StringWriter()
        val pemWriter = PemWriter(stringWriter)
        pemWriter.writeObject(PemObject("PRIVATE KEY", privateKey.encoded))
        pemWriter.close()
        return stringWriter.toString()
    }

    fun decodePemPrivateKey(pemPrivateKey: String): ByteArray {
        val reader = PemReader(StringReader(pemPrivateKey))
        val pemObject = reader.readPemObject()
        reader.close()
        return pemObject.content
    }

    fun decodePemCertificate(pemCertificate: String): ByteArray {
        val reader = PemReader(StringReader(pemCertificate))
        val pemObject = reader.readPemObject()
        reader.close()
        return pemObject.content
    }
}