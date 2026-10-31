package io.github.firebirks.booruvault.util

import okio.Buffer

/**
 * Buffers that libraries keep for reuse, in static pools, hold whatever last went through them,
 * in clear and alive: kotlinx.serialization's the JSON the vault seals (cookies, settings, item
 * metadata), okio's the sites' responses. A lock cannot release them, so they are kept empty.
 */
object LibraryBuffers {
    /**
     * Has kotlinx.serialization keep no buffer once it is done with it. Its pools read their size
     * once, when first used, so this must run before any JSON is encoded or decoded.
     */
    fun keepNoJsonBuffers() {
        System.setProperty("kotlinx.serialization.json.pool.size", "0")
    }

    /**
     * Overwrites the segments okio keeps for reuse. It keeps up to [POOL_BYTES] in each bucket
     * and picks the bucket from the thread's id, so this goes through a thread for each bucket.
     */
    fun wipeOkioSegments() {
        val buckets = Integer.highestOneBit(Runtime.getRuntime().availableProcessors() * 2 - 1)
        val left = (0 until buckets).toMutableSet()
        while (left.isNotEmpty()) {
            val thread = Thread(::overwriteOwnBucket)
            if (left.remove((thread.id and (buckets - 1L)).toInt())) {
                thread.start()
                thread.join()
            }
        }
    }

    /** Takes every segment of this thread's bucket, and one more, fills each, then gives them back. */
    private fun overwriteOwnBucket() {
        val zeros = ByteArray(SEGMENT_SIZE)
        val buffer = Buffer()
        repeat(POOL_BYTES / SEGMENT_SIZE + 1) { buffer.write(zeros) }
        buffer.clear()
    }

    private const val SEGMENT_SIZE = 8192
    private const val POOL_BYTES = 64 * 1024
}
