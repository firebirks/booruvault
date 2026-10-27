package io.github.firebirks.booruvault.ui.settings

import androidx.compose.material3.IconButton
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.WarningAmber
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.firebirks.booruvault.BuildConfig
import io.github.firebirks.booruvault.R
import io.github.firebirks.booruvault.booru.Rating
import io.github.firebirks.booruvault.net.TorManager
import io.github.firebirks.booruvault.net.TorStatus
import io.github.firebirks.booruvault.settings.GridSize
import io.github.firebirks.booruvault.ui.common.LocalContainer
import io.github.firebirks.booruvault.ui.common.PasswordField
import io.github.firebirks.booruvault.ui.common.findActivity
import io.github.firebirks.booruvault.ui.common.labelRes
import io.github.firebirks.booruvault.ui.common.LocalLinkOpener
import io.github.firebirks.booruvault.ui.common.problemText
import io.github.firebirks.booruvault.ui.vault.MIN_PASSWORD
import io.github.firebirks.booruvault.ui.vault.enrollBiometric
import io.github.firebirks.booruvault.util.hasGooglePlayServices
import io.github.firebirks.booruvault.vault.BiometricKey
import io.github.firebirks.booruvault.vault.VaultHeaderException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Settings with its own small back stack: main page, source list, source editor. */
@Composable
fun SettingsTab() {
    var page by rememberSaveable { mutableStateOf("main") }
    BackHandler(enabled = page != "main") { page = if (page.startsWith("source:")) "sources" else "main" }
    when {
        page == "main" -> SettingsMain(onSources = { page = "sources" }, onRisky = { page = "risky" })
        page == "risky" -> RiskySettings(onBack = { page = "main" })
        page == "sources" -> SourcesScreen(onBack = { page = "main" }, onEdit = { page = "source:$it" })
        else -> SourceEditor(id = page.removePrefix("source:").takeIf { it != NEW_SOURCE }, onDone = { page = "sources" })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsMain(onSources: () -> Unit, onRisky: () -> Unit) {
    val container = LocalContainer.current
    val repo = container.settings
    val settings by repo.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var changePassword by remember { mutableStateOf(false) }
    var destroy by remember { mutableStateOf(false) }
    val biometricOn by container.vault.biometric.collectAsStateWithLifecycle()

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_title)) }) }, contentWindowInsets = WindowInsets(0)) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            SectionHeader(stringResource(R.string.settings_section_sources))
            val count = pluralStringResource(R.plurals.settings_sources_count, settings.sources.size, settings.sources.size)
            val inUse = stringResource(R.string.settings_sources_in_use, settings.selectedSource?.name ?: stringResource(R.string.settings_sources_none))
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_manage_sources)) },
                supportingContent = { Text("$count · $inUse") },
                trailingContent = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null) },
                modifier = Modifier.clickable(onClick = onSources),
            )

            SectionHeader(stringResource(R.string.settings_section_content))
            ChoiceRow(
                title = stringResource(R.string.settings_ratings_title),
                subtitle = stringResource(R.string.settings_ratings_hint),
                options = Rating.entries,
                label = { stringResource(it.labelRes()) },
                isSelected = { it in settings.allowedRatings },
                onSelect = { rating ->
                    val next = if (rating in settings.allowedRatings) settings.allowedRatings - rating else settings.allowedRatings + rating
                    if (next.isNotEmpty()) scope.launch { repo.setRatings(next) }
                },
            )
            BlacklistEditor(settings.blacklist) { scope.launch { repo.setBlacklist(it) } }
            ChoiceRow(stringResource(R.string.settings_grid_size), null, GridSize.entries, { stringResource(it.labelRes()) }, { it == settings.gridSize }) {
                scope.launch { repo.setGridSize(it) }
            }
            ChoiceRow(stringResource(R.string.settings_page_size), null, listOf(20, 40, 60, 100), { "$it" }, { it == settings.pageSize }) {
                scope.launch { repo.setPageSize(it) }
            }
            SwitchRow(stringResource(R.string.settings_viewer_original), stringResource(R.string.settings_viewer_original_hint), settings.viewerOriginal) {
                scope.launch { repo.setViewerOriginal(it) }
            }
            SwitchRow(stringResource(R.string.settings_download_original), stringResource(R.string.settings_download_original_hint), settings.downloadOriginal) {
                scope.launch { repo.setDownloadOriginal(it) }
            }

            SectionHeader(stringResource(R.string.settings_section_vault))
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_lock_now)) },
                supportingContent = { Text(stringResource(R.string.settings_lock_now_hint)) },
                leadingContent = { Icon(Icons.Outlined.Lock, null) },
                modifier = Modifier.clickable { container.vault.lock() },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_change_password)) },
                leadingContent = { Icon(Icons.Outlined.Password, null) },
                modifier = Modifier.clickable { changePassword = true },
            )
            val rekey by container.rekeyer.progress.collectAsStateWithLifecycle()
            rekey?.let { progress ->
                ListItem(
                    headlineContent = { Text(stringResource(R.string.rekey_title)) },
                    supportingContent = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.rekey_progress, progress.done, progress.total))
                            LinearProgressIndicator(
                                progress = { if (progress.total == 0) 0f else progress.done.toFloat() / progress.total },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    },
                )
            }
            val activity = context.findActivity() as? FragmentActivity
            if (activity != null && BiometricKey.isAvailable(context)) {
                SwitchRow(stringResource(R.string.settings_biometric), stringResource(R.string.settings_biometric_hint), biometricOn) { on ->
                    if (on) {
                        enrollBiometric(activity, container.vault, container.scope) { message ->
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        container.scope.launch {
                            container.vault.disableBiometric()
                            BiometricKey.delete()
                        }
                    }
                }
            }
            ChoiceRow(
                title = stringResource(R.string.settings_auto_lock),
                subtitle = stringResource(R.string.settings_auto_lock_hint),
                options = listOf(0, 30, 60, 300, 900),
                label = {
                    when (it) {
                        0 -> stringResource(R.string.auto_lock_immediately)
                        in 1..59 -> stringResource(R.string.auto_lock_seconds, it)
                        else -> stringResource(R.string.auto_lock_minutes, it / 60)
                    }
                },
                isSelected = { it == settings.autoLockSeconds },
                onSelect = { scope.launch { repo.setAutoLock(it) } },
            )
            SwitchRow(
                stringResource(R.string.settings_secure_screen),
                stringResource(R.string.settings_secure_screen_hint),
                settings.secureScreen,
            ) { scope.launch { repo.setSecureScreen(it) } }

            SectionHeader(stringResource(R.string.settings_section_network))
            TorSettings()
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_risky)) },
                supportingContent = { Text(stringResource(R.string.settings_risky_hint)) },
                leadingContent = { Icon(Icons.Outlined.WarningAmber, null, tint = MaterialTheme.colorScheme.error) },
                trailingContent = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null) },
                modifier = Modifier.clickable(onClick = onRisky),
            )

            SectionHeader(stringResource(R.string.settings_section_data))
            val cleared = stringResource(R.string.settings_cookies_cleared)
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_cookies)) },
                supportingContent = { Text(stringResource(R.string.settings_cookies_hint)) },
                trailingContent = {
                    TextButton(onClick = {
                        container.cookies.clear()
                        Toast.makeText(context, cleared, Toast.LENGTH_SHORT).show()
                    }) { Text(stringResource(R.string.action_clear)) }
                },
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_delete_vault), color = MaterialTheme.colorScheme.error) },
                supportingContent = { Text(stringResource(R.string.settings_delete_vault_hint)) },
                leadingContent = { Icon(Icons.Outlined.DeleteForever, null, tint = MaterialTheme.colorScheme.error) },
                modifier = Modifier.clickable { destroy = true },
            )

            SectionHeader(stringResource(R.string.settings_section_about))
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_about_version, BuildConfig.VERSION_NAME)) },
                supportingContent = { Text(stringResource(R.string.settings_about_text)) },
            )
        }
    }

    if (changePassword) ChangePasswordDialog(onDismiss = { changePassword = false })
    if (destroy) DestroyVaultDialog(onDismiss = { destroy = false })
}

/**
 * Everything that trades protection for convenience, behind one door and off by default, each
 * with what it costs spelled out.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RiskySettings(onBack: () -> Unit) {
    val container = LocalContainer.current
    val repo = container.settings
    val settings by repo.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val gms = remember { hasGooglePlayServices(context) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_risky)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.action_back)) } },
            )
        },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            Text(
                stringResource(R.string.settings_risky_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(16.dp),
            )
            SwitchRow(
                stringResource(R.string.settings_download_locked),
                stringResource(if (settings.downloadWhileLocked) R.string.settings_download_locked_on else R.string.settings_download_locked_off),
                settings.downloadWhileLocked,
            ) { scope.launch { repo.setDownloadWhileLocked(it) } }
            HorizontalDivider()
            SwitchRow(
                stringResource(R.string.settings_hidden_browser),
                stringResource(if (settings.hiddenBrowser) R.string.settings_hidden_browser_on else R.string.settings_hidden_browser_off),
                settings.hiddenBrowser,
            ) { scope.launch { repo.setHiddenBrowser(it) } }
            HorizontalDivider()
            SwitchRow(
                stringResource(R.string.settings_links_outside_tor),
                stringResource(if (settings.linksOutsideTor) R.string.settings_links_outside_tor_on else R.string.settings_links_outside_tor_off),
                settings.linksOutsideTor,
            ) { scope.launch { repo.setLinksOutsideTor(it) } }
            HorizontalDivider()
            if (!gms) {
                SwitchRow(
                    stringResource(R.string.settings_safe_browsing),
                    stringResource(R.string.settings_safe_browsing_unavailable),
                    checked = false,
                    enabled = false,
                ) {}
            } else {
                SwitchRow(
                    stringResource(R.string.settings_safe_browsing),
                    stringResource(if (settings.webSafeBrowsing) R.string.settings_safe_browsing_on else R.string.settings_safe_browsing_off),
                    settings.webSafeBrowsing,
                ) { scope.launch { repo.setWebSafeBrowsing(it) } }
            }
        }
    }
}

@Composable
private fun BlacklistEditor(stored: String, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(stored) }
    var edited by remember { mutableStateOf(false) }
    LaunchedEffect(stored) { if (!edited) text = stored }
    LaunchedEffect(text) {
        if (!edited) return@LaunchedEffect
        delay(600)
        if (text != stored) onSave(text)
    }
    OutlinedTextField(
        value = text,
        onValueChange = {
            edited = true
            text = it
        },
        label = { Text(stringResource(R.string.settings_blacklist)) },
        supportingText = { Text(stringResource(R.string.settings_blacklist_hint)) },
        minLines = 3,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun ChangePasswordDialog(onDismiss: () -> Unit) {
    val vault = LocalContainer.current.vault
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var old by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var wrong by remember { mutableStateOf(false) }
    val valid = old.isNotEmpty() && new.length >= MIN_PASSWORD && new == confirm
    val changed = stringResource(R.string.password_changed)
    val headerMismatch = stringResource(R.string.vault_header_mismatch)
    val tooShort = new.isNotEmpty() && new.length < MIN_PASSWORD
    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text(stringResource(R.string.settings_change_password)) },
        text = {
            // Four blocks of content and a keyboard: on a small phone only scrolling fits them.
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.password_change_rekey_note), style = MaterialTheme.typography.bodySmall)
                PasswordField(
                    old, { old = it; wrong = false }, stringResource(R.string.password_current), imeAction = ImeAction.Next,
                    isError = wrong, supportingText = if (wrong) stringResource(R.string.password_current_wrong) else null, enabled = !working,
                )
                PasswordField(
                    new, { new = it }, stringResource(R.string.password_new), imeAction = ImeAction.Next, enabled = !working,
                    isError = tooShort,
                    supportingText = if (tooShort) stringResource(R.string.password_too_short, MIN_PASSWORD) else null,
                )
                PasswordField(
                    confirm, { confirm = it }, stringResource(R.string.password_new_confirm), enabled = !working,
                    isError = confirm.isNotEmpty() && confirm != new,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valid && !working, onClick = {
                working = true
                scope.launch {
                    val ok = try {
                        vault.changePassword(old.toCharArray(), new.toCharArray())
                    } catch (_: VaultHeaderException) {
                        working = false
                        Toast.makeText(context, headerMismatch, Toast.LENGTH_LONG).show()
                        onDismiss()
                        return@launch
                    }
                    working = false
                    if (ok) {
                        // The biometric copy held the old key; the Keystore key goes with it.
                        BiometricKey.delete()
                        Toast.makeText(context, changed, Toast.LENGTH_SHORT).show()
                        onDismiss()
                    } else {
                        wrong = true
                    }
                }
            }) {
                if (working) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !working) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun DestroyVaultDialog(onDismiss: () -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    var typed by remember { mutableStateOf("") }
    val word = stringResource(R.string.delete_vault_word)
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.DeleteForever, null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(stringResource(R.string.delete_vault_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.delete_vault_text))
                OutlinedTextField(typed, { typed = it }, label = { Text(stringResource(R.string.delete_vault_type_to_confirm, word)) }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(enabled = sameConfirmWord(typed, word), onClick = {
                container.downloads.cancelAll()
                scope.launch {
                    container.vault.destroy()
                    BiometricKey.delete()
                    onDismiss()
                }
            }) { Text(stringResource(R.string.delete_vault_confirm), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/**
 * The typed confirmation matches regardless of case and of diacritics, which keyboards render
 * differently (Hindi ं/ँ, Polish Ń, Turkish İ): it guards against a slip, not against an attacker.
 */
private fun sameConfirmWord(typed: String, word: String): Boolean {
    fun plain(s: String) = java.text.Normalizer.normalize(s.trim(), java.text.Normalizer.Form.NFKD)
        .replace(Regex("\\p{Mn}+"), "")
        .lowercase(java.util.Locale.ROOT)
    return plain(typed).isNotEmpty() && plain(typed) == plain(word)
}

@Composable
private fun TorSettings() {
    val container = LocalContainer.current
    val tor = container.tor
    val state by tor.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var askInstall by remember { mutableStateOf(false) }
    var verifying by remember { mutableStateOf(false) }
    val links = LocalLinkOpener.current

    SwitchRow(stringResource(R.string.tor_use), stringResource(R.string.tor_use_hint), state.enabled) { on ->
        if (on && !tor.isOrbotInstalled()) askInstall = true else tor.setEnabled(on)
    }
    if (state.enabled) {
        val status = when (state.status) {
            TorStatus.ON -> stringResource(R.string.tor_status_on, state.socksPort)
            TorStatus.STARTING -> stringResource(R.string.tor_status_starting)
            TorStatus.NOT_INSTALLED -> stringResource(R.string.tor_status_not_installed)
            TorStatus.STARTS_DISABLED -> stringResource(R.string.tor_status_starts_disabled)
            TorStatus.OFF, TorStatus.DISABLED -> stringResource(R.string.tor_status_off)
        }
        ListItem(
            headlineContent = { Text(status) },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    state.verifiedExit?.let { Text(stringResource(R.string.tor_verified, it), color = MaterialTheme.colorScheme.primary) }
                    state.verifyError?.let { Text(problemText(it), color = MaterialTheme.colorScheme.error) }
                    Text(stringResource(R.string.tor_vpn_hint), style = MaterialTheme.typography.bodySmall)
                }
            },
        )
        TorPortField(tor, state.socksPort, state.reportedPort)
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { tor.requestStart() }) { Text(stringResource(R.string.tor_start_orbot)) }
            OutlinedButton(onClick = { tor.orbotLaunchIntent()?.let { context.startActivity(it) } }) { Text(stringResource(R.string.tor_open_orbot)) }
            OutlinedButton(enabled = !verifying, onClick = {
                verifying = true
                scope.launch {
                    tor.verify(container.http, container.json)
                    verifying = false
                }
            }) { Text(stringResource(if (verifying) R.string.tor_verifying else R.string.tor_verify)) }
        }
    }

    if (askInstall) {
        val chooserTitle = stringResource(R.string.tor_install_with)
        AlertDialog(
            onDismissRequest = { askInstall = false },
            title = { Text(stringResource(R.string.tor_need_orbot_title)) },
            text = { Text(stringResource(R.string.tor_need_orbot_text)) },
            confirmButton = {
                TextButton(onClick = {
                    askInstall = false
                    openStoreFor(context, TorManager.ORBOT_PACKAGE, chooserTitle) { links.open(ORBOT_DOWNLOAD) }
                }) { Text(stringResource(R.string.tor_search_stores)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    askInstall = false
                    links.open(ORBOT_DOWNLOAD)
                }) { Text(stringResource(R.string.tor_orbot_site)) }
            },
        )
    }
}

/**
 * The local port the app connects to Orbot on. It is applied when the field is left or confirmed,
 * not at every keystroke: each change drops the connections opened along the old route.
 */
@Composable
private fun TorPortField(tor: TorManager, port: Int, reported: Int?) {
    var text by remember(port) { mutableStateOf(port.toString()) }
    val valid = text.toIntOrNull()?.let { it in 1..65535 } == true
    fun commit() {
        text.toIntOrNull()?.let(tor::setSocksPort)
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it.filter(Char::isDigit).take(5) },
        label = { Text(stringResource(R.string.tor_port)) },
        supportingText = {
            Text(
                when {
                    !valid -> stringResource(R.string.tor_port_invalid)
                    reported != null && reported != port -> stringResource(R.string.tor_port_reported, reported)
                    else -> stringResource(R.string.tor_port_hint)
                },
            )
        },
        isError = !valid,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commit() }),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).onFocusChanged { if (!it.isFocused) commit() },
    )
}

private const val ORBOT_DOWNLOAD = "https://orbot.app/download/"

/**
 * Opens [packageName]'s page in an installed app store, letting the user choose when there are
 * several (F-Droid, Droid-ify, Aurora, Play...), or [fallbackUrl] when there is none.
 */
private fun openStoreFor(context: android.content.Context, packageName: String, chooserTitle: String, fallback: () -> Unit) {
    val market = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=$packageName"))
    val stores = context.packageManager.queryIntentActivities(market, 0)
    when {
        stores.isEmpty() -> fallback()
        stores.size == 1 -> context.startActivity(market)
        else -> context.startActivity(android.content.Intent.createChooser(market, chooserTitle))
    }
}
