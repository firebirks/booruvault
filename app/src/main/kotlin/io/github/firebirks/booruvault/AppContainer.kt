package io.github.firebirks.booruvault

import android.content.Context
import android.os.Build
import androidx.lifecycle.ProcessLifecycleOwner
import io.github.firebirks.booruvault.booru.BooruApi
import io.github.firebirks.booruvault.booru.BooruApiFactory
import io.github.firebirks.booruvault.booru.BooruSource
import io.github.firebirks.booruvault.booru.GelbooruApi
import io.github.firebirks.booruvault.booru.USER_AGENT
import io.github.firebirks.booruvault.download.DownloadManager
import io.github.firebirks.booruvault.net.Challenge
import io.github.firebirks.booruvault.net.HiddenBrowser
import io.github.firebirks.booruvault.net.SecureCookieJar
import io.github.firebirks.booruvault.net.TorManager
import io.github.firebirks.booruvault.net.TorUnreachableException
import io.github.firebirks.booruvault.settings.SettingsRepository
import io.github.firebirks.booruvault.vault.AutoLock
import io.github.firebirks.booruvault.vault.VaultImageFetcher
import io.github.firebirks.booruvault.vault.VaultImageKeyer
import io.github.firebirks.booruvault.vault.VaultManager
import io.github.firebirks.booruvault.vault.VaultRekeyer
import io.github.firebirks.booruvault.vault.VaultState
import io.github.firebirks.booruvault.vault.VaultStore
import io.github.firebirks.booruvault.vault.VaultTransfer
import coil3.ImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import coil3.serviceLoaderEnabled
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Everything with application lifetime, wired by hand.
 *
 * Nothing about what the user browses or keeps is stored in clear: settings, sources, cookies and
 * the download queue are sealed in the vault next to its contents, and the image cache is memory
 * only. The one clear file is the vault header, which holds no user data.
 */
class AppContainer(private val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
        encodeDefaults = true
    }

    private val vaultDir = File(context.filesDir, "vault")
    val vault = VaultManager(vaultDir, json)
    val settings = SettingsRepository(vaultDir, vault, json, scope)
    val cookies = SecureCookieJar(vaultDir, vault, json, scope)

    val tor = TorManager(context, scope)

    /**
     * The app's only HTTP client: API calls, images (Coil), video (Media3), downloads. With Tor on
     * it can reach the network only through Orbot, and cannot resolve names itself.
     */
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .proxySelector(tor.proxySelector)
        .dns(tor.dns)
        .cookieJar(cookies)
        .addInterceptor { chain ->
            val request = chain.request()
            // A site whose anti-bot check the user passed only honours the browser that passed it.
            val agent = cookies.userAgentFor(request.url.host) ?: request.header("User-Agent") ?: USER_AGENT
            val builder = request.newBuilder().header("User-Agent", agent)
            // Some image hosts (img*.gelbooru.com) redirect a request without a Referer to an HTML
            // hotlink page. Their own origin satisfies them and says nothing about the user.
            if (request.header("Referer") == null) builder.header("Referer", "${request.url.scheme}://${request.url.host}/")
            try {
                chain.proceed(builder.build())
            } catch (e: IOException) {
                if (tor.enabled && !chain.call().isCanceled() && e !is TorUnreachableException) throw TorUnreachableException(e)
                throw e
            }
        }
        .build()

    val vaultStore = VaultStore(vaultDir, vault, json, scope)
    val rekeyer = VaultRekeyer(vaultDir, vault, vaultStore, scope)
    val transfer = VaultTransfer(context, vaultStore)
    val hiddenBrowser = HiddenBrowser(context, tor, cookies, settings, json)
    val downloads = DownloadManager(context, { api(it) }, http, vaultStore, vault, settings, json, vaultDir, scope)
    val autoLock = AutoLock(vault, settings, scope)

    init {
        // Connections opened along the old route must not be reused along the new one.
        tor.onRouteChanged = {
            http.dispatcher.cancelAll()
            http.connectionPool.evictAll()
        }
        // Downloads allowed to outlive the lock keep the site cookies they need, and only them.
        cookies.retainWhileLocked = { downloads.workingWhileLocked }
        downloads.onLockedWorkDone = { cookies.releaseIfLocked() }
    }

    val imageLoader: ImageLoader = ImageLoader.Builder(context)
        .components {
            add(OkHttpNetworkFetcherFactory(callFactory = { http }))
            add(VaultImageFetcher.Factory(vaultStore))
            add(VaultImageKeyer())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) add(AnimatedImageDecoder.Factory()) else add(GifDecoder.Factory())
        }
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.25).build() }
        // No disk cache: browsed images would sit on storage in clear.
        .diskCache(null)
        // Only the components above: a service-loaded network fetcher would bring its own client.
        .serviceLoaderEnabled(false)
        .crossfade(true)
        .build()

    fun api(source: BooruSource): BooruApi =
        BooruApiFactory.create(source, http, json, hiddenBrowser.takeIf { settings.settings.value.hiddenBrowser })

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(autoLock)
        scope.launch(Dispatchers.IO) {
            removeLegacyPlaintext()
            Challenge.wipeLeftovers(context)
        }
        scope.launch {
            var wasUnlocked = false
            vault.state.collect { state ->
                if (state is VaultState.Unlocked) {
                    wasUnlocked = true
                } else if (wasUnlocked) {
                    wasUnlocked = false
                    forgetSession()
                }
            }
        }
    }

    /**
     * After a lock: drop what is still cached outside the components that clear themselves, then
     * ask for a collection so released key objects and buffers are reclaimed promptly; native key
     * copies are cleared by BoringSSL when freed. Kotlin cannot overwrite immutable strings or
     * memory the runtime has already moved, so this narrows the window rather than guaranteeing it.
     * Browsing requests are cancelled by their own screens; downloads the user lets run are not.
     */
    private suspend fun forgetSession() {
        GelbooruApi.forgetSession()
        imageLoader.memoryCache?.clear()
        http.connectionPool.evictAll()
        // Let cancelled transfers unwind and the UI drop its state first.
        delay(500)
        Runtime.getRuntime().gc()
        @Suppress("DEPRECATION")
        System.runFinalization()
        Runtime.getRuntime().gc()
    }

    /** Files the first builds kept in clear: settings, the clearance store, the image cache. */
    private fun removeLegacyPlaintext() {
        File(context.filesDir, "datastore").deleteRecursively()
        context.deleteSharedPreferences("clearance")
        File(context.cacheDir, "images").deleteRecursively()
        File(context.cacheDir, "image_cache").deleteRecursively()
    }
}
