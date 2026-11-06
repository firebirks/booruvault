package io.github.firebirks.booruvault.ui.browse

import io.github.firebirks.booruvault.net.TorState
import io.github.firebirks.booruvault.net.TorStatus
import io.github.firebirks.booruvault.util.Problem
import org.junit.Assert.assertEquals
import org.junit.Test

/** The onion in Browse says what the settings' verdict says, in one of four looks. */
class TorBadgeTest {
    private val on = TorState(enabled = true, status = TorStatus.ON)

    @Test
    fun `Tor switched off is off, whatever was known before`() {
        assertEquals(TorBadge.OFF, torBadge(TorState(enabled = false, status = TorStatus.DISABLED)))
        assertEquals(TorBadge.OFF, torBadge(on.copy(enabled = false, verifiedExit = "1.2.3.4")))
    }

    @Test
    fun `a verified exit is verified`() {
        assertEquals(TorBadge.VERIFIED, torBadge(on.copy(verifiedExit = "1.2.3.4")))
    }

    @Test
    fun `a listener that answers, or a port being probed, is being checked`() {
        assertEquals(TorBadge.CHECKING, torBadge(on))
        assertEquals(TorBadge.CHECKING, torBadge(on.copy(status = TorStatus.CHECKING)))
    }

    @Test
    fun `Orbot silent or missing, or an exit check that failed, is not working`() {
        assertEquals(TorBadge.FAILED, torBadge(on.copy(status = TorStatus.OFF)))
        assertEquals(TorBadge.FAILED, torBadge(on.copy(status = TorStatus.NOT_INSTALLED)))
        assertEquals(TorBadge.FAILED, torBadge(on.copy(verifyError = Problem(Problem.Code.NOT_THROUGH_TOR))))
    }
}
