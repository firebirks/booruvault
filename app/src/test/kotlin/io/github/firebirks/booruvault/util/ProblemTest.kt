package io.github.firebirks.booruvault.util

import io.github.firebirks.booruvault.booru.BooruException
import io.github.firebirks.booruvault.net.TorUnreachableException
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class ProblemTest {
    @Test
    fun `a timeout along the cause chain reads as a timeout`() {
        assertEquals(Problem.Code.TIMEOUT, SocketTimeoutException("Connect timed out").toProblem().code)
        // A page read wraps what the network threw in a network problem of its own.
        val wrapped = BooruException(Problem.Code.NETWORK, "Read timed out", SocketTimeoutException("Read timed out"))
        assertEquals(Problem.Code.TIMEOUT, wrapped.toProblem().code)
    }

    @Test
    fun `a more telling problem than the timeout under it wins`() {
        assertEquals(Problem.Code.TOR_UNREACHABLE, TorUnreachableException(SocketTimeoutException()).toProblem().code)
    }

    @Test
    fun `any other network failure stays a network error with its message`() {
        assertEquals(Problem(Problem.Code.NETWORK, "Connection reset"), IOException("Connection reset").toProblem())
    }
}
