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
import okio.Buffer
import okio.source

/** Coil model for vault content. Decrypted bytes stay in memory; nothing reaches the disk cache. */
data class VaultImage(val id: String, val thumbnail: Boolean)

class VaultImageFetcher(
    private val data: VaultImage,
    private val options: Options,
    private val store: VaultStore,
) : Fetcher {
    override suspend fun fetch(): FetchResult = withContext(Dispatchers.IO) {
        val sealed = if (data.thumbnail) store.openThumbnail(data.id) else store.openMedia(data.id)
        val buffer = Buffer()
        sealed.use { file -> file.inputStream().source().use { buffer.writeAll(it) } }
        SourceFetchResult(
            source = ImageSource(buffer, options.fileSystem),
            mimeType = if (data.thumbnail) null else store.item(data.id)?.mimeType,
            dataSource = DataSource.DISK,
        )
    }

    class Factory(private val store: VaultStore) : Fetcher.Factory<VaultImage> {
        override fun create(data: VaultImage, options: Options, imageLoader: ImageLoader): Fetcher =
            VaultImageFetcher(data, options, store)
    }
}

class VaultImageKeyer : Keyer<VaultImage> {
    override fun key(data: VaultImage, options: Options): String = "vault:${data.id}:${data.thumbnail}"
}
