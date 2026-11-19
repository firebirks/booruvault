package io.github.firebirks.booruvault.net

import io.github.firebirks.booruvault.util.Problem
import org.junit.Assert.assertEquals
import org.junit.Test

/** What a request held back by the exit check can expect: to go out, to wait, or neither. */
class TorVerdictTest {
    private val on = TorState(enabled = true, status = TorStatus.ON)

    @Test
    fun `Tor switched off, or an exit verified, lets the request out`() {
        assertEquals(true, TorManager.verdict(TorState(enabled = false, status = TorStatus.DISABLED)))
        assertEquals(true, TorManager.verdict(on.copy(verifiedExit = "1.2.3.4")))
    }

    @Test
    fun `a listener that answers, or a port being probed, is worth waiting for`() {
        assertEquals(null, TorManager.verdict(on))
        assertEquals(null, TorManager.verdict(on.copy(status = TorStatus.CHECKING)))
    }

    @Test
    fun `Orbot silent or missing, or an exit check that failed, is not waited for`() {
        assertEquals(false, TorManager.verdict(on.copy(status = TorStatus.OFF)))
        assertEquals(false, TorManager.verdict(on.copy(status = TorStatus.NOT_INSTALLED)))
        assertEquals(false, TorManager.verdict(on.copy(verifyError = Problem(Problem.Code.NOT_THROUGH_TOR))))
    }

    @Test
    fun `an exit check speaks for the listener it went through and for no other`() {
        val verified = on.copy(socksPort = 9050)
        assertEquals(true, TorManager.speaksFor(verified, 9050, lossesThen = 3, lossesNow = 3))
        // The listener was found gone while the check was on its way: whatever answers now was not checked.
        assertEquals(false, TorManager.speaksFor(verified, 9050, lossesThen = 3, lossesNow = 4))
        assertEquals(false, TorManager.speaksFor(verified, 9150, lossesThen = 3, lossesNow = 3))
        assertEquals(false, TorManager.speaksFor(verified.copy(enabled = false), 9050, lossesThen = 3, lossesNow = 3))
    }

    @Test
    fun `the exit is checked again only after a long absence`() {
        assertEquals(false, TorManager.exitStale(0))
        assertEquals(false, TorManager.exitStale(TorManager.RECHECK_AFTER_MS - 1))
        assertEquals(true, TorManager.exitStale(TorManager.RECHECK_AFTER_MS))
        assertEquals(true, TorManager.exitStale(60 * 60_000L))
    }
}
