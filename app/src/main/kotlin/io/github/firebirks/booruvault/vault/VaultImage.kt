package io.github.firebirks.booruvault.vault

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.buffer
import okio.source
import java.io.FilterInputStream

/** Coil model for vault content. Decrypted bytes stay in memory; nothing reaches the disk cache. */
data class VaultImage(val id: String, val thumbnail: Boolean)

class VaultImageFetcher(
    private val data: VaultImage,
    private val options: Options,
    private val store: VaultStore,
) : Fetcher {
    override suspend fun fetch(): FetchResult = withContext(Dispatchers.IO) {
        val sealed = if (data.thumbnail) store.openThumbnail(data.id) else store.openMedia(data.id)
        try {
            // Decrypted a chunk at a time as the decoder reads, and closed with the source: an
            // image of hundreds of megabytes is never whole in memory before it is decoded.
            val input = object : FilterInputStream(sealed.inputStream()) {
                override fun close() = sealed.close()
            }
            SourceFetchResult(
                source = ImageSource(input.source().buffer(), options.fileSystem),
                mimeType = if (data.thumbnail) null else store.item(data.id)?.mimeType,
                dataSource = DataSource.DISK,
            )
        } catch (e: Throwable) {
            sealed.close()
            throw e
        }
    }

    class Factory(private val store: VaultStore) : Fetcher.Factory<VaultImage> {
        override fun create(data: VaultImage, options: Options, imageLoader: ImageLoader): Fetcher =
            VaultImageFetcher(data, options, store)
    }
}

class VaultImageKeyer : Keyer<VaultImage> {
    override fun key(data: VaultImage, options: Options): String = "vault:${data.id}:${data.thumbnail}"
}
