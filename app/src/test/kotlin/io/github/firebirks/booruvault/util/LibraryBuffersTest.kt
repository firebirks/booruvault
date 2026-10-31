package io.github.firebirks.booruvault.util

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import okio.Buffer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLClassLoader
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** What passed through a library's reusable buffers is not left in them. */
class LibraryBuffersTest {
    private val secret = "cf_clearance=SECRET-pikachu-4242"

    @Test
    fun jsonEncodedOnceTheSwitchIsSetStaysInNoPool() {
        LibraryBuffers.keepNoJsonBuffers()
        // The pool reads its size once, when first used; a class loader of its own starts it afresh.
        val urls = listOf(Json::class.java, KSerializer::class.java, Unit::class.java)
            .map { it.protectionDomain.codeSource.location }.toTypedArray()
        URLClassLoader(urls, null).use { loader ->
            val json = loader.loadClass("kotlinx.serialization.json.Json").getField("Default").get(null)
            val strings = loader.loadClass("kotlinx.serialization.internal.StringSerializer").getField("INSTANCE").get(null)
            val strategy = loader.loadClass("kotlinx.serialization.SerializationStrategy")
            json.javaClass.getMethod("encodeToString", strategy, Any::class.java).invoke(json, strings, secret)

            val pool = loader.loadClass("kotlinx.serialization.json.internal.CharArrayPool").getField("INSTANCE").get(null)
            val arrays = loader.loadClass("kotlinx.serialization.json.internal.CharArrayPoolBase").getDeclaredField("arrays")
                .apply { isAccessible = true }.get(pool) as Collection<*>
            assertFalse("left in the pool", arrays.any { String(it as CharArray).contains(secret) })
        }
    }

    @Test
    fun noPooledOkioSegmentKeepsWhatPassedThrough() {
        // On several threads, as OkHttp reads, so that several of the pool's buckets hold it.
        val text = secret.repeat(1000)
        List(16) { thread { Buffer().apply { writeUtf8(text) }.clear() } }.forEach { it.join() }
        assertTrue("left in the pool to begin with", pooledSegments().any { String(it, Charsets.ISO_8859_1).contains(secret) })

        LibraryBuffers.wipeOkioSegments()
        assertFalse("left in the pool after the wipe", pooledSegments().any { String(it, Charsets.ISO_8859_1).contains(secret) })
    }

    /** The data of every segment okio holds for reuse, read from its private buckets. */
    private fun pooledSegments(): List<ByteArray> {
        val pool = Class.forName("okio.SegmentPool")
        val buckets = pool.getDeclaredField("hashBuckets").apply { isAccessible = true }.get(null) as Array<*>
        val segment = Class.forName("okio.Segment")
        val data = segment.getDeclaredField("data")
        val next = segment.getDeclaredField("next")
        return buckets.flatMap { bucket ->
            generateSequence((bucket as AtomicReference<*>).get()) { next.get(it) }.map { data.get(it) as ByteArray }.toList()
        }
    }
}
