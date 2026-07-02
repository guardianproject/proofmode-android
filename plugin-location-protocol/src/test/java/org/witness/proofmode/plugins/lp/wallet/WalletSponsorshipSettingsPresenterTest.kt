package org.witness.proofmode.plugins.lp.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletSponsorshipSettingsPresenterTest {

    private val presenter = WalletSponsorshipSettingsPresenter()

    @Test
    fun displayProjectId_showsOverrideWhenSet() {
        assertEquals("override-uuid", presenter.displayProjectId("override-uuid", "build-default"))
    }

    @Test
    fun displayProjectId_showsEmptyWhenNoOverride() {
        assertEquals("", presenter.displayProjectId(null, "build-default"))
        assertEquals("", presenter.displayProjectId("", "build-default"))
    }

    @Test
    fun validateProjectIdInput_rejectsInvalidUuid() {
        assertFalse(presenter.validateProjectIdInput("not-a-uuid").isValid)
    }

    @Test
    fun validateProjectIdInput_acceptsValidUuid() {
        assertTrue(presenter.validateProjectIdInput("550e8400-e29b-41d4-a716-446655440000").isValid)
    }

    @Test
    fun validateProjectIdInput_acceptsBlank() {
        assertTrue(presenter.validateProjectIdInput("").isValid)
        assertTrue(presenter.validateProjectIdInput("   ").isValid)
    }

    @Test
    fun resolvePersistedOverride_blankAfterTrim_clearsOverride() {
        assertNull(presenter.resolvePersistedOverride("", buildDefault = "build-default"))
        assertNull(presenter.resolvePersistedOverride("   ", buildDefault = "build-default"))
    }

    @Test
    fun resolvePersistedOverride_matchingBuildDefault_doesNotPersist() {
        assertNull(presenter.resolvePersistedOverride("build-default", buildDefault = "build-default"))
    }

    @Test
    fun resolvePersistedOverride_distinctFromDefault_persistsOverride() {
        assertEquals(
            "550e8400-e29b-41d4-a716-446655440000",
            presenter.resolvePersistedOverride(
                "550e8400-e29b-41d4-a716-446655440000",
                buildDefault = "build-default",
            ),
        )
    }
}
