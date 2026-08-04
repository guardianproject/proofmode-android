package org.witness.proofmode.plugins.wallet.infra

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.witness.proofmode.plugins.wallet.infra.factory.WalletSessionStore

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WalletSessionStoreTest {

    @Before
    fun setUp() {
        FakeAndroidKeyStoreProvider.setup()
    }

    @Test
    fun saveAndLoadChainId_roundTrips() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = WalletSessionStore(context)
        store.saveChainId("eip155:11155111")
        assertEquals("eip155:11155111", store.loadChainId())
    }

    @Test
    fun sponsorTransactionsEnabled_defaultsTrue() {
        val store = WalletSessionStore(ApplicationProvider.getApplicationContext())
        assertTrue(store.isSponsorTransactionsEnabled())
    }

    @Test
    fun sponsorTransactionsEnabled_roundTrips() {
        val store = WalletSessionStore(ApplicationProvider.getApplicationContext())
        store.saveSponsorTransactionsEnabled(false)
        assertFalse(store.isSponsorTransactionsEnabled())
        store.saveSponsorTransactionsEnabled(true)
        assertTrue(store.isSponsorTransactionsEnabled())
    }

    @Test
    fun zeroDevProjectIdOverride_defaultsNull() {
        val store = WalletSessionStore(ApplicationProvider.getApplicationContext())
        assertNull(store.loadZeroDevProjectIdOverride())
    }

    @Test
    fun zeroDevProjectIdOverride_roundTrips() {
        val store = WalletSessionStore(ApplicationProvider.getApplicationContext())
        val uuid = "550e8400-e29b-41d4-a716-446655440000"
        store.saveZeroDevProjectIdOverride(uuid)
        assertEquals(uuid, store.loadZeroDevProjectIdOverride())
    }

    @Test
    fun clear_removesSponsorshipKeys() {
        val store = WalletSessionStore(ApplicationProvider.getApplicationContext())
        store.saveChainId("eip155:1")
        store.saveSponsorTransactionsEnabled(false)
        store.saveZeroDevProjectIdOverride("550e8400-e29b-41d4-a716-446655440000")
        store.clear()
        assertNull(store.loadChainId())
        assertTrue(store.isSponsorTransactionsEnabled())
        assertNull(store.loadZeroDevProjectIdOverride())
    }
}
