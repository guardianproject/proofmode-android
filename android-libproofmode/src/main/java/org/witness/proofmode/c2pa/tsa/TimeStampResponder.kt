package org.witness.proofmode.c2pa.tsa

import org.bouncycastle.asn1.cmp.PKIFailureInfo
import org.bouncycastle.asn1.cmp.PKIStatus
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.cert.jcajce.JcaCertStore
import org.bouncycastle.cms.SignerInfoGenerator
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoGeneratorBuilder
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder
import org.bouncycastle.tsp.TSPAlgorithms
import org.bouncycastle.tsp.TSPException
import org.bouncycastle.tsp.TimeStampRequest
import org.bouncycastle.tsp.TimeStampResponseGenerator
import org.bouncycastle.tsp.TimeStampTokenGenerator
import java.math.BigInteger
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date

/**
 * The protocol half of the local Time Stamp Authority: turns a DER-encoded
 * RFC 3161 `TimeStampReq` into a DER-encoded `TimeStampResp`. Transport-free so it
 * can be exercised on the JVM; [LocalTsaServer] puts it behind HTTP (RFC 3161 §3.4).
 *
 * The token is signed with [privateKey] and identifies [certificate] through an
 * ESSCertIDv2 (SHA-256) signing-certificate attribute. [certificate] must carry a
 * critical id-kp-timeStamping extended key usage (RFC 3161 §2.3), otherwise
 * BouncyCastle refuses to build the token generator.
 *
 * `genTime` comes from [clock]; the token's policy and accuracy record which
 * [TimeSource] supplied it.
 */
class TimeStampResponder(
    privateKey: PrivateKey,
    certificate: X509Certificate,
    private val clock: () -> TimeReading = { TimeReading(Date(), TimeSource.DEVICE_CLOCK) },
    private val random: SecureRandom = SecureRandom(),
) {
    companion object {
        /** Content-Type of a request and a reply, RFC 3161 §3.4. */
        const val CONTENT_TYPE_QUERY = "application/timestamp-query"
        const val CONTENT_TYPE_REPLY = "application/timestamp-reply"
    }

    /** One per source: policy and accuracy are fixed per TimeStampTokenGenerator. */
    private val tokenGenerators: Map<TimeSource, TimeStampTokenGenerator>

    init {
        val digestProvider = JcaDigestCalculatorProviderBuilder().build()
        val signerInfoGenerator: SignerInfoGenerator =
            JcaSimpleSignerInfoGeneratorBuilder().build(signatureAlgorithm(privateKey), privateKey, certificate)
        tokenGenerators = TimeSource.entries.associateWith { source ->
            TimeStampTokenGenerator(
                signerInfoGenerator,
                digestProvider.get(AlgorithmIdentifier(NISTObjectIdentifiers.id_sha256)),
                source.policy,
            ).apply {
                source.accuracySeconds?.let { setAccuracySeconds(it) }
                // Only emitted when the request sets certReq (RFC 3161 §2.4.1); c2pa-rs does.
                addCertificates(JcaCertStore(listOf(certificate)))
            }
        }
    }

    /**
     * Never throws for a malformed or unsupported request: RFC 3161 §2.4.2 wants a
     * `rejection` status with a failure reason, and that is what the client gets.
     */
    fun respond(requestDer: ByteArray): ByteArray {
        val reading = clock()
        val responseGenerator = TimeStampResponseGenerator(tokenGenerators.getValue(reading.source), TSPAlgorithms.ALLOWED)
        val request = try {
            TimeStampRequest(requestDer)
        } catch (e: Exception) {
            return responseGenerator
                .generateFailResponse(PKIStatus.REJECTION, PKIFailureInfo.badDataFormat, "malformed TimeStampReq")
                .encoded
        }

        // A reqPolicy other than the one this time source can honour must be
        // refused (RFC 3161 §2.4.1).
        if (request.reqPolicy != null && request.reqPolicy != reading.source.policy) {
            return responseGenerator
                .generateFailResponse(PKIStatus.REJECTION, PKIFailureInfo.unacceptedPolicy, "unsupported policy")
                .encoded
        }
        return try {
            // generate() validates the request (digest algorithm, imprint length) and
            // turns a violation into a rejection response rather than throwing.
            responseGenerator.generate(request, nextSerial(), reading.time).encoded
        } catch (e: TSPException) {
            responseGenerator
                .generateFailResponse(PKIStatus.REJECTION, PKIFailureInfo.systemFailure, e.message ?: "system failure")
                .encoded
        }
    }

    /** Unique, positive, at most 160 bits (RFC 3161 §2.4.2, `serialNumber`). */
    private fun nextSerial(): BigInteger = BigInteger(128, random).setBit(127)

    private fun signatureAlgorithm(key: PrivateKey): String = when (key.algorithm) {
        "EC" -> "SHA256withECDSA"
        "RSA" -> "SHA256withRSA"
        else -> throw IllegalArgumentException("Unsupported TSA key algorithm: ${key.algorithm}")
    }
}
