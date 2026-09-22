package android.net

import android.net.Uri
import android.os.Parcel

/**
 * Test-only [Uri] stand-in for unit tests that must populate a [Uri]-typed field but never read it.
 *
 * Lives in the `android.net` package so it can invoke [Uri]'s package-private constructor (the
 * AGP mockable android.jar narrows the implicit constructor to package-private, blocking
 * subclassing from other packages). Every abstract accessor returns a neutral default; the
 * concrete [toString] keeps diagnostics readable.
 *
 * Required because, on JDK 25, Robolectric 4.13's bundled ASM cannot instrument
 * `android.net.Uri.parse` (unsupported class file major version 69) and mockito-inline cannot
 * retransform `android.net.Uri`, so neither Robolectric nor a mock can produce a non-null [Uri]
 * in this environment.
 */
internal class FakeUri : Uri() {
    override fun buildUpon(): Uri.Builder? = null
    override fun getAuthority(): String? = null
    override fun getEncodedAuthority(): String? = null
    override fun getEncodedFragment(): String? = null
    override fun getEncodedPath(): String? = null
    override fun getEncodedQuery(): String? = null
    override fun getEncodedSchemeSpecificPart(): String? = null
    override fun getEncodedUserInfo(): String? = null
    override fun getFragment(): String? = null
    override fun getHost(): String? = null
    override fun getLastPathSegment(): String? = null
    override fun getPath(): String? = null
    override fun getPathSegments(): MutableList<String> = mutableListOf()
    override fun getPort(): Int = -1
    override fun getQuery(): String? = null
    override fun getScheme(): String? = null
    override fun getSchemeSpecificPart(): String? = null
    override fun getUserInfo(): String? = null
    override fun isHierarchical(): Boolean = false
    override fun isRelative(): Boolean = false
    override fun toString(): String = "FakeUri"
    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = System.identityHashCode(this)
    override fun describeContents(): Int = 0
    override fun writeToParcel(dest: Parcel, flags: Int) { /* no-op */ }
}
