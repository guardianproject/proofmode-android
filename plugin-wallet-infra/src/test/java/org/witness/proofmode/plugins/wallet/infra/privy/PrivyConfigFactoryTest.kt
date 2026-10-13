package org.witness.proofmode.plugins.wallet.infra.privy

import io.privy.breadcrumbs.PrivyBreadcrumb
import io.privy.breadcrumbs.WalletCreateStarted
import io.privy.logging.PrivyLogLevel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.witness.proofmode.plugins.wallet.infra.model.WalletSdkConfig
import timber.log.Timber

class PrivyConfigFactoryTest {

    private data class Record(val tag: String?, val priority: Int, val message: String)

    private class RecordingTree : Timber.Tree() {
        val records = mutableListOf<Record>()
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            records += Record(tag, priority, message)
        }
    }

    private class ThrowingTree : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            throw IllegalStateException("logging failed")
        }
    }

    // PrivyBreadcrumb is a sealed interface, so tests use a public SDK implementation.
    private fun breadcrumb(): PrivyBreadcrumb = WalletCreateStarted("ethereum")

    @Before
    fun setUp() {
        Timber.uprootAll()
    }

    @After
    fun tearDown() {
        Timber.uprootAll()
    }

    @Test
    fun `format includes event timestamp and data`() {
        val out = PrivyConfigFactory.format(
            "wallet.create.started",
            1L,
            mapOf("chain-type" to "ethereum"),
        )
        assertTrue(out, out.contains("wallet.create.started"))
        assertTrue(out, out.contains("1"))
        assertTrue(out, out.contains("chain-type"))
        assertTrue(out, out.contains("ethereum"))
    }

    @Test
    fun `format drops error key`() {
        val out = PrivyConfigFactory.format(
            "wallet.create.failed",
            2L,
            mapOf("chain-type" to "ethereum", "error" to "secret-failure-detail"),
        )
        assertFalse(out, out.contains("error"))
        assertFalse(out, out.contains("secret-failure-detail"))
        assertTrue(out, out.contains("chain-type"))
    }

    @Test
    fun `format drops error-message key`() {
        val out = PrivyConfigFactory.format(
            "wallet.bridge.failed",
            3L,
            mapOf("operation" to "sign", "error-message" to "raw-bridge-text"),
        )
        assertFalse(out, out.contains("error"))
        assertFalse(out, out.contains("raw-bridge-text"))
        assertTrue(out, out.contains("operation"))
    }

    @Test
    fun `handler logs under breadcrumb tag`() {
        val tree = RecordingTree()
        Timber.plant(tree)

        PrivyConfigFactory.handler.receive(
            breadcrumb()
        )

        assertEquals(1, tree.records.size)
        assertEquals(PrivyConfigFactory.BREADCRUMB_TAG, tree.records[0].tag)
        assertTrue(tree.records[0].message.contains("wallet.create.started"))
    }

    @Test
    fun `handler swallows exceptions from logging`() {
        Timber.plant(ThrowingTree())

        PrivyConfigFactory.handler.receive(
            breadcrumb()
        )
    }

    @Test
    fun `create sets handler and NONE log level`() {
        val cfg = PrivyConfigFactory.create(WalletSdkConfig("id", "client"))

        assertEquals("id", cfg.appId)
        assertEquals("client", cfg.appClientId)
        assertEquals(PrivyLogLevel.NONE, cfg.logLevel)
        assertNotNull(cfg.breadcrumbHandler)
    }
}
