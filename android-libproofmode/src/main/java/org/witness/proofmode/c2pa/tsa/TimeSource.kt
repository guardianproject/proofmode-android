package org.witness.proofmode.c2pa.tsa

import org.bouncycastle.asn1.ASN1ObjectIdentifier
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Where the local TSA's `genTime` came from. Each source is its own TSA policy
 * (RFC 3161 §2.4.2 `policy`), so a verifier can tell from the token alone which
 * clock it is trusting. Both OIDs are minted under the X.667 UUID arc (2.25).
 */
enum class TimeSource(val policy: ASN1ObjectIdentifier, val accuracySeconds: Int?) {
    /** The device wall clock, which the user can set freely. No accuracy is claimed. */
    DEVICE_CLOCK(ASN1ObjectIdentifier("2.25.207054283364233390866773993094590391497"), null),

    /**
     * UTC from a GNSS fix, carried forward to "now" on the monotonic
     * elapsed-realtime clock, so neither the fix's age nor the wall clock matters.
     * One second covers fix-reporting latency and oscillator drift over [GnssTime.MAX_FIX_AGE_MS].
     */
    GNSS(ASN1ObjectIdentifier("2.25.97624713405329848468366125004926407021"), 1),
}

data class TimeReading(val time: Date, val source: TimeSource)

object GnssTime {
    /** At a pessimistic 50 ppm oscillator drift, 4 h stays under the claimed 1 s (~0.72 s). */
    val MAX_FIX_AGE_MS = TimeUnit.HOURS.toMillis(4)

    /**
     * Current UTC from a GNSS fix taken [fixUtcMillis] (its `Location.getTime()`) at
     * [fixElapsedNanos] (its `getElapsedRealtimeNanos()`), given the elapsed-realtime
     * clock now reads [nowElapsedNanos]. Null if the fix is from the future (another
     * boot) or too old to vouch for the present.
     */
    fun extrapolate(fixUtcMillis: Long, fixElapsedNanos: Long, nowElapsedNanos: Long): Date? {
        if (fixUtcMillis <= 0 || fixElapsedNanos <= 0) return null
        // Compare in nanos: converting first would truncate a slightly-future fix to age 0.
        val ageNanos = nowElapsedNanos - fixElapsedNanos
        if (ageNanos < 0 || ageNanos > TimeUnit.MILLISECONDS.toNanos(MAX_FIX_AGE_MS)) return null
        return Date(fixUtcMillis + TimeUnit.NANOSECONDS.toMillis(ageNanos))
    }
}
