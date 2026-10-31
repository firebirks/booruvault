package io.github.firebirks.booruvault.ui.browse

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The screen's view model outlives a lock while the screen is open, and a lock from another tab
 * does not reach a value nothing is collecting any more: such a value must not stay behind.
 */
class ShownStateTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun aValueNoScreenShowsAnyMoreIsDropped() = runBlocking {
        val tags = MutableStateFlow(listOf("pikachu"))
        val shown = tags.stateIn(scope, whileShown(stopTimeoutMillis = 50), emptyList())
        val screen = scope.launch { shown.collect {} }
        withTimeout(2000) { shown.first { it.isNotEmpty() } }
        screen.cancel()
        delay(500)
        assertEquals(emptyList<String>(), shown.value)
    }
}
