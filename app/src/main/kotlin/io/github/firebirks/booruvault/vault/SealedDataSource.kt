package io.github.firebirks.booruvault.vault

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import io.github.firebirks.booruvault.vault.crypto.SealedFile
import java.io.IOException

/** Streams a vault video to ExoPlayer, decrypting only the chunks being played. */
@UnstableApi
class SealedDataSource(private val store: VaultStore) : BaseDataSource(false) {
    private var file: SealedFile? = null
    private var uri: Uri? = null
    private var position = 0L
    private var remaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val id = dataSpec.uri.lastPathSegment ?: throw IOException("bad vault uri")
        val sealed = store.openMedia(id)
        file = sealed
        uri = dataSpec.uri
        position = dataSpec.position
        if (position > sealed.size) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else sealed.size - position
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val n = file?.read(position, buffer, offset, minOf(length.toLong(), remaining).toInt()) ?: return C.RESULT_END_OF_INPUT
        if (n < 0) return C.RESULT_END_OF_INPUT
        position += n
        remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        try {
            file?.close()
        } finally {
            file = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    class Factory(private val store: VaultStore) : DataSource.Factory {
        override fun createDataSource(): DataSource = SealedDataSource(store)
    }

    companion object {
        fun uriFor(id: String): Uri = Uri.parse("vault://media/$id")
    }
}
