package io.github.firebirks.booruvault.ui

import io.github.firebirks.booruvault.ui.common.ProvideLinkOpener
import androidx.compose.ui.unit.sp
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.LocalContentColor
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.BasicText
import io.github.firebirks.booruvault.R
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import io.github.firebirks.booruvault.ui.common.CenteredProgress
import io.github.firebirks.booruvault.ui.common.findActivity
import io.github.firebirks.booruvault.ui.vault.VaultSetupScreen
import io.github.firebirks.booruvault.ui.vault.enrollBiometric
import io.github.firebirks.booruvault.vault.BiometricKey
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.firebirks.booruvault.AppContainer
import io.github.firebirks.booruvault.ui.browse.BrowseScreen
import io.github.firebirks.booruvault.ui.browse.BrowseViewModel
import io.github.firebirks.booruvault.ui.browse.PostViewer
import io.github.firebirks.booruvault.ui.common.ChallengeLauncher
import io.github.firebirks.booruvault.ui.common.ChallengeRequest
import io.github.firebirks.booruvault.ui.common.ChallengeScreen
import io.github.firebirks.booruvault.ui.common.LocalChallenge
import io.github.firebirks.booruvault.ui.common.LocalContainer
import io.github.firebirks.booruvault.ui.common.containerViewModel
import io.github.firebirks.booruvault.ui.downloads.DownloadsScreen
import io.github.firebirks.booruvault.ui.settings.OrbotSetup
import io.github.firebirks.booruvault.ui.settings.SettingsTab
import io.github.firebirks.booruvault.ui.vault.RouteChoiceScreen
import io.github.firebirks.booruvault.ui.vault.UnlockScreen
import io.github.firebirks.booruvault.ui.vault.VaultTab
import io.github.firebirks.booruvault.ui.vault.VaultViewModel
import io.github.firebirks.booruvault.ui.vault.VaultViewer
import io.github.firebirks.booruvault.vault.VaultState
import kotlinx.coroutines.flow.MutableStateFlow

private enum class Tab(@param:StringRes val label: Int, val icon: ImageVector) {
    BROWSE(R.string.tab_browse, Icons.Outlined.Explore),
    VAULT(R.string.tab_vault, Icons.Outlined.Lock),
    DOWNLOADS(R.string.tab_downloads, Icons.Outlined.Download),
    SETTINGS(R.string.tab_settings, Icons.Outlined.Settings),
}

private val TrailSaver = listSaver<List<Tab>, String>(save = { trail -> trail.map { it.name } }, restore = { names -> names.map(Tab::valueOf) })

/**
 * Nothing opens without the vault: settings, sources and everything browsed live inside it, so
 * the app is the lock screen until it is unlocked.
 */
@Composable
fun AppRoot(container: AppContainer, requestedTab: MutableStateFlow<String?>) {
    CompositionLocalProvider(LocalContainer provides container) {
        val ready by container.settings.ready.collectAsStateWithLifecycle()
        val vaultState by container.vault.state.collectAsStateWithLifecycle()
        val routeChosen by container.tor.routeChosen.collectAsStateWithLifecycle()
        var offerBiometric by rememberSaveable { mutableStateOf(false) }
        var orbotSetup by rememberSaveable { mutableStateOf(false) }
        when (vaultState) {
            VaultState.Missing -> VaultSetupScreen(onCreated = { offerBiometric = true })
            VaultState.Locked -> UnlockScreen(
                title = stringResource(R.string.unlock_title_app),
                subtitle = stringResource(R.string.unlock_subtitle_app),
            )
            is VaultState.Unlocked -> when {
                !ready -> CenteredProgress(stringResource(R.string.opening_vault))
                // Asked before anything goes out: the main screens are what make requests.
                !routeChosen -> RouteChoiceScreen(onChosen = { useTor ->
                    container.tor.chooseRoute(useTor)
                    orbotSetup = useTor
                })
                else -> {
                    ProvideLinkOpener {
                        MainScaffold(requestedTab)
                        if (orbotSetup) OrbotSetup(onDone = { orbotSetup = false })
                    }
                    if (offerBiometric && !orbotSetup) BiometricOffer(onDone = { offerBiometric = false })
                }
            }
        }
    }
}

/** One line that shrinks to fit: tab names run long in some languages ("Téléchargements"). */
@Composable
private fun TabLabel(text: String) {
    val style = LocalTextStyle.current
    BasicText(
        text,
        maxLines = 1,
        style = style.copy(color = LocalContentColor.current),
        autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = style.fontSize, stepSize = 0.5.sp),
    )
}

@Composable
private fun BiometricOffer(onDone: () -> Unit) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val activity = context.findActivity() as? FragmentActivity
    if (activity == null || !BiometricKey.isAvailable(context)) {
        LaunchedEffect(Unit) { onDone() }
        return
    }
    AlertDialog(
        onDismissRequest = onDone,
        icon = { Icon(Icons.Outlined.Fingerprint, null) },
        title = { Text(stringResource(R.string.biometric_offer_title)) },
        text = { Text(stringResource(R.string.biometric_offer_text)) },
        confirmButton = {
            TextButton(onClick = {
                onDone()
                // The dialog is gone by the time the prompt answers; the app's scope is not.
                enrollBiometric(activity, container.vault, container.scope) { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
            }) { Text(stringResource(R.string.action_turn_on)) }
        },
        dismissButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.action_not_now)) } },
    )
}

@Composable
private fun MainScaffold(requestedTab: MutableStateFlow<String?>) {
    val container = LocalContainer.current
    var trail by rememberSaveable(stateSaver = TrailSaver) { mutableStateOf(listOf(Tab.BROWSE)) }
    val tab = trail.last()
    fun open(item: Tab) {
        trail = trail.visit(item)
    }
    // Index into the browse results, or id of a vault item, of the open full-screen viewer.
    var postViewer by rememberSaveable { mutableStateOf<Int?>(null) }
    var vaultViewer by rememberSaveable { mutableStateOf<String?>(null) }
    val holder = rememberSaveableStateHolder()
    val browse = containerViewModel { BrowseViewModel(it) }
    val vault = containerViewModel { VaultViewModel(it) }
    val requested by requestedTab.collectAsStateWithLifecycle()
    val vaultState by container.vault.state.collectAsStateWithLifecycle()
    val tasks by container.downloads.tasks.collectAsStateWithLifecycle()
    val active = tasks.count { it.active }

    LaunchedEffect(requested) {
        if (requested == "downloads") {
            postViewer = null
            vaultViewer = null
            open(Tab.DOWNLOADS)
            requestedTab.value = null
        }
    }

    // Registered before any screen's own handler, so those still win while they are enabled:
    // Back undoes what was done in the tab first, then returns to the tab visited before.
    BackHandler(enabled = trail.size > 1) { trail = trail.dropLast(1) }

    var challenge by remember { mutableStateOf<ChallengeRequest?>(null) }
    val launcher = remember { ChallengeLauncher { source, url, mode, onDone -> challenge = ChallengeRequest(source, url, mode, onDone) } }

    CompositionLocalProvider(LocalChallenge provides launcher) {
    Box(Modifier.fillMaxSize()) {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    Tab.entries.forEach { item ->
                        NavigationBarItem(
                            selected = tab == item,
                            onClick = { open(item) },
                            icon = {
                                val icon = if (item == Tab.VAULT && vaultState is VaultState.Unlocked) Icons.Outlined.LockOpen else item.icon
                                if (item == Tab.DOWNLOADS && active > 0) {
                                    BadgedBox(badge = { Badge { Text("$active") } }) { Icon(icon, null) }
                                } else {
                                    Icon(icon, null)
                                }
                            },
                            label = { TabLabel(stringResource(item.label)) },
                        )
                    }
                }
            },
            contentWindowInsets = WindowInsets(0),
        ) { padding ->
            Box(Modifier.padding(padding).consumeWindowInsets(padding)) {
                holder.SaveableStateProvider(tab.name) {
                    when (tab) {
                        Tab.BROWSE -> BrowseScreen(onOpenPost = { postViewer = it }, onNeedVault = { open(Tab.VAULT) })
                        Tab.VAULT -> VaultTab(onOpenItem = { vaultViewer = it })
                        Tab.DOWNLOADS -> DownloadsScreen()
                        Tab.SETTINGS -> SettingsTab()
                    }
                }
            }
        }

        postViewer?.let { index ->
            PostViewer(
                startIndex = index,
                onClose = { postViewer = null },
                onSearchTag = { tag ->
                    postViewer = null
                    open(Tab.BROWSE)
                    browse.search(tag)
                },
                onNeedVault = {
                    postViewer = null
                    open(Tab.VAULT)
                },
            )
        }
        vaultViewer?.let { id ->
            VaultViewer(
                startId = id,
                onClose = { vaultViewer = null },
                onSearchTag = { tag ->
                    vaultViewer = null
                    vault.query = tag
                },
            )
        }
        challenge?.let { request ->
            ChallengeScreen(request) { solved ->
                challenge = null
                if (solved) request.onSolved()
            }
        }
    }
    }
}

/** Tabs in the order last visited, the current one last: Back returns to the one before it. */
internal fun <T> List<T>.visit(tab: T): List<T> = if (lastOrNull() == tab) this else this - tab + tab
