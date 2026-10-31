package io.github.firebirks.booruvault.net

import org.junit.Assert.assertEquals
import org.junit.Test

/** The WebView's storage is wiped step by step, and a step that fails stops none of the rest. */
class WipeStepsTest {
    @Test
    fun aStepThatThrowsLeavesTheOthersToRun() {
        val ran = mutableListOf<String>()
        runEach(
            { ran += "cache" },
            { throw IllegalStateException("the WebView is gone") },
            { ran += "cookies" },
            { ran += "storage" },
        )
        assertEquals(listOf("cache", "cookies", "storage"), ran)
    }
}
