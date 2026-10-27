package app.booruvault.vault

import app.booruvault.booru.Rating
import kotlinx.serialization.Serializable

/** One file in the vault. Kept only inside the sealed index; never written anywhere in clear. */
@Serializable
data class VaultItem(
    val id: String,
    val addedAt: Long,
    val fileName: String,
    val mimeType: String,
    val size: Long,
    val width: Int = 0,
    val height: Int = 0,
    val hasThumbnail: Boolean = false,
    val sourceId: String? = null,
    val sourceName: String? = null,
    val postId: Long? = null,
    val postUrl: String? = null,
    val originalUrl: String? = null,
    val md5: String? = null,
    val rating: Rating = Rating.UNKNOWN,
    val tags: List<String> = emptyList(),
) {
    val isVideo: Boolean get() = mimeType.startsWith("video/")
    val isAnimated: Boolean get() = mimeType == "image/gif"
}
