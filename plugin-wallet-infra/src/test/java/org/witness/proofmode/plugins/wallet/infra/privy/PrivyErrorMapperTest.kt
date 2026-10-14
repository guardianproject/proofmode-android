package org.witness.proofmode.plugins.wallet.infra.privy

import io.privy.network.ApiResult
import io.privy.network.NoNetworkException
import io.privy.network.PrivyApiException
import io.privy.network.PrivyHttpErrorResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.witness.proofmode.plugins.wallet.infra.exception.WalletAuthException
import org.witness.proofmode.plugins.wallet.infra.exception.WalletCreationFailedException
import org.witness.proofmode.plugins.wallet.infra.exception.WalletInvalidCodeException
import org.witness.proofmode.plugins.wallet.infra.exception.WalletNetworkUnavailableException
import org.witness.proofmode.plugins.wallet.infra.exception.WalletSessionExpiredException

class PrivyErrorMapperTest {

    private fun apiException(
        statusCode: Int?,
        body: PrivyHttpErrorResponse? = null,
        message: String = "api error",
        cause: Throwable = RuntimeException("api cause")
    ) = PrivyApiException(statusCode, body, message, cause)

    @Test
    fun `login invalid_credentials maps to invalid code`() {
        val t = apiException(
            422,
            PrivyHttpErrorResponse(code = "invalid_credentials", error = "Invalid credentials")
        )
        val mapped = PrivyErrorMapper.mapLoginError(t)
        assertTrue(mapped is WalletInvalidCodeException)
        assertSame(t, mapped.cause)
    }

    @Test
    fun `login 422 without body maps to invalid code`() {
        val mapped = PrivyErrorMapper.mapLoginError(apiException(422, null))
        assertTrue(mapped is WalletInvalidCodeException)
    }

    @Test
    fun `login 429 keeps too many requests wording`() {
        val mapped = PrivyErrorMapper.mapLoginError(apiException(429, null))
        assertTrue(mapped is WalletAuthException)
        assertTrue(mapped.message!!.contains("too many requests"))
    }

    @Test
    fun `login NoNetworkException maps to network unavailable`() {
        val mapped = PrivyErrorMapper.mapLoginError(NoNetworkException)
        assertTrue(mapped is WalletNetworkUnavailableException)
    }

    @Test
    fun `login finds network error in cause chain`() {
        val mapped = PrivyErrorMapper.mapLoginError(RuntimeException("wrapped", NoNetworkException))
        assertTrue(mapped is WalletNetworkUnavailableException)
    }

    @Test
    fun `login unknown error keeps message and cause`() {
        val t = RuntimeException("boom")
        val mapped = PrivyErrorMapper.mapLoginError(t)
        assertTrue(mapped is WalletAuthException)
        assertEquals("boom", mapped.message)
        assertSame(t, mapped.cause)
    }

    @Test
    fun `login passes WalletException through`() {
        val t = WalletSessionExpiredException()
        assertSame(t, PrivyErrorMapper.mapLoginError(t))
    }

    @Test
    fun `creation 401 maps to session expired`() {
        val mapped = PrivyErrorMapper.mapCreationError(apiException(401, null))
        assertTrue(mapped is WalletSessionExpiredException)
        assertNotNull(mapped.cause)
    }

    @Test
    fun `creation NoNetworkException maps to network unavailable`() {
        val mapped = PrivyErrorMapper.mapCreationError(NoNetworkException)
        assertTrue(mapped is WalletNetworkUnavailableException)
    }

    @Test
    fun `creation unknown error maps to creation failed`() {
        val t = IllegalStateException("nope")
        val mapped = PrivyErrorMapper.mapCreationError(t)
        assertTrue(mapped is WalletCreationFailedException)
        assertSame(t, mapped.cause)
    }

    private fun realOffline() = PrivyApiException(
        null,
        null,
        "The device is not connected to the internet.",
        ApiResult.Error.NoNetworkError.exception
    )

    @Test
    fun `login real sdk offline error maps to network unavailable`() {
        val t = realOffline()
        val mapped = PrivyErrorMapper.mapLoginError(t)
        assertTrue(mapped is WalletNetworkUnavailableException)
        assertSame(t, mapped.cause)
    }

    @Test
    fun `creation real sdk offline error maps to network unavailable`() {
        val mapped = PrivyErrorMapper.mapCreationError(realOffline())
        assertTrue(mapped is WalletNetworkUnavailableException)
    }

    @Test
    fun `offline error wrapped in WalletAuthException is network`() {
        assertTrue(PrivyErrorMapper.isNetworkError(WalletAuthException("x", realOffline())))
    }

    @Test
    fun `api error with other cause is not network`() {
        assertFalse(
            PrivyErrorMapper.isNetworkError(
                PrivyApiException(500, null, "boom", RuntimeException())
            )
        )
    }
}
