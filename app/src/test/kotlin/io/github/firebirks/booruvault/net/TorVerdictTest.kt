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
}
