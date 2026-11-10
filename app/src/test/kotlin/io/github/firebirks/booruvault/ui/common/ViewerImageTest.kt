package io.github.firebirks.booruvault.ui.common

import coil3.network.HttpException
import coil3.network.NetworkHeaders
import coil3.network.NetworkResponse
import io.github.firebirks.booruvault.util.Problem
import io.github.firebirks.booruvault.vault.VaultLockedException
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class ViewerImageTest {
    private val url = "https://cdn.example.com/original/ab/cd/abcd.jpg"

    private fun refused(code: Int, vararg headers: Pair<String, String>): HttpException {
        val builder = NetworkHeaders.Builder()
        headers.forEach { (name, value) -> builder.set(name, value) }
        return HttpException(NetworkResponse(code = code, headers = builder.build()))
    }

    @Test
    fun `a check in front of the file is passed at the file's own address`() {
        assertEquals(Problem(Problem.Code.CHALLENGE, url), refused(403, "cf-mitigated" to "challenge").remoteImageProblem(url))
        // The image loader wraps what the fetch threw.
        val wrapped = IllegalStateException("load failed", refused(403, "CF-Mitigated" to "Challenge"))
        assertEquals(Problem(Problem.Code.CHALLENGE, url), wrapped.remoteImageProblem(url))
    }

    @Test
    fun `a refusal without a check says its status`() {
        assertEquals(Problem(Problem.Code.HTTP_STATUS, "403"), refused(403).remoteImageProblem(url))
        assertEquals(Problem(Problem.Code.HTTP_STATUS, "404"), refused(404).remoteImageProblem(url))
    }

    @Test
    fun `a site that asks for a pause or is down is told as such`() {
        assertEquals(Problem(Problem.Code.RATE_LIMITED), refused(429).remoteImageProblem(url))
        assertEquals(Problem(Problem.Code.SERVER_DOWN, "503"), refused(503).remoteImageProblem(url))
    }

    @Test
    fun `a post without a file has none to show`() {
        assertEquals(Problem(Problem.Code.NO_FILE), IOException("no data").remoteImageProblem(null))
    }

    @Test
    fun `a network failure reads as the rest of the app reads it`() {
        assertEquals(Problem.Code.TIMEOUT, SocketTimeoutException("Read timed out").remoteImageProblem(url).code)
        assertEquals(Problem(Problem.Code.NETWORK, "Connection reset"), IOException("Connection reset").remoteImageProblem(url))
    }

    @Test
    fun `a vault file that cannot be shown is a file that cannot be read`() {
        assertEquals(Problem(Problem.Code.FILE_UNREADABLE, "bad tag"), IOException("bad tag").vaultImageProblem())
        assertEquals(Problem(Problem.Code.FILE_UNREADABLE, "IllegalStateException"), IllegalStateException().vaultImageProblem())
    }

    @Test
    fun `the vault's own reason wins over a file that cannot be read`() {
        assertEquals(Problem.Code.VAULT_LOCKED, IllegalStateException("decode", VaultLockedException()).vaultImageProblem().code)
    }
}
