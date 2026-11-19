package io.github.firebirks.booruvault.settings

import io.github.firebirks.booruvault.booru.Rating
import io.github.firebirks.booruvault.booru.Rating.EXPLICIT
import io.github.firebirks.booruvault.booru.Rating.GENERAL
import io.github.firebirks.booruvault.booru.Rating.QUESTIONABLE
import io.github.firebirks.booruvault.booru.Rating.SENSITIVE
import io.github.firebirks.booruvault.booru.Rating.UNKNOWN
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Questionable and explicit posts are for adults, and so may be unrated ones: a site that gives
 * no ratings lets its explicit posts through with the rest. Turning any of them on asks the user
 * to confirm being an adult, unless an adult rating already on stands for that confirmation.
 */
class AdultConsentTest {
    private val fresh = setOf(GENERAL)

    @Test
    fun `turning on unrated asks, as the adult ratings do`() {
        assertTrue(UNKNOWN.asksAdultConsent(fresh))
        assertTrue(QUESTIONABLE.asksAdultConsent(fresh))
        assertTrue(EXPLICIT.asksAdultConsent(fresh + SENSITIVE))
    }

    @Test
    fun `general and sensitive posts ask nothing`() {
        assertFalse(GENERAL.asksAdultConsent(setOf(SENSITIVE)))
        assertFalse(SENSITIVE.asksAdultConsent(fresh))
    }

    @Test
    fun `an adult rating already on stands for the confirmation`() {
        assertFalse(UNKNOWN.asksAdultConsent(fresh + EXPLICIT))
        assertFalse(QUESTIONABLE.asksAdultConsent(fresh + EXPLICIT))
    }

    @Test
    fun `unrated already on does not, as it may have been turned on before it asked`() {
        assertTrue(EXPLICIT.asksAdultConsent(fresh + UNKNOWN))
    }

    @Test
    fun `a rating already on asks nothing when turned off`() {
        Rating.entries.forEach { assertFalse(it.asksAdultConsent(Rating.entries.toSet())) }
    }
}
