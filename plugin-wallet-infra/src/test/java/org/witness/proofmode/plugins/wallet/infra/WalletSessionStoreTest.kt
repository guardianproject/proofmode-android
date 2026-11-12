package org.witness.proofmode.plugins.wallet.infra

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun sponsoredChains_neverSaved_isNotYetKnown_butSavedEmptyIsKnownEmpty() {
        val store = WalletSessionStore(ApplicationProvider.getApplicationContext())
        assertNull(store.loadSponsoredChains(PROJECT_A))
        store.saveSponsoredChains(PROJECT_A, emptyList())
        assertEquals(emptyList<String>(), store.loadSponsoredChains(PROJECT_A))
    }

    @Test
    fun sponsoredChains_otherProjectId_isNotYetKnown_andOverrideChangeReplacesEntry() {
        val store = WalletSessionStore(ApplicationProvider.getApplicationContext())
        store.saveSponsoredChains(PROJECT_A, listOf("eip155:1", "eip155:11155111"))
        assertNull(store.loadSponsoredChains(PROJECT_B))
        assertEquals(listOf("eip155:1", "eip155:11155111"), store.loadSponsoredChains(PROJECT_A))

        store.saveSponsoredChains(PROJECT_B, listOf("eip155:8453"))
        assertEquals(listOf("eip155:8453"), store.loadSponsoredChains(PROJECT_B))
        assertNull(store.loadSponsoredChains(PROJECT_A))
    }

    private companion object {
        const val PROJECT_A = "550e8400-e29b-41d4-a716-446655440000"
        const val PROJECT_B = "6ba7b810-9dad-11d1-80b4-00c04fd430c8"
    }
}
