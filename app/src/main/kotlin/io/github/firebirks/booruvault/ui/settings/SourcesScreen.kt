package io.github.firebirks.booruvault.ui.settings

import io.github.firebirks.booruvault.util.toProblem
import io.github.firebirks.booruvault.ui.common.text
import io.github.firebirks.booruvault.ui.common.labelRes
import io.github.firebirks.booruvault.ui.common.UiText
import io.github.firebirks.booruvault.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.firebirks.booruvault.booru.BooruChallengeException
import io.github.firebirks.booruvault.booru.BooruEngine
import io.github.firebirks.booruvault.booru.BooruSource
import io.github.firebirks.booruvault.booru.MoebooruApi
import io.github.firebirks.booruvault.booru.Presets
import io.github.firebirks.booruvault.ui.common.ChallengeMode
import io.github.firebirks.booruvault.ui.common.LocalChallenge
import io.github.firebirks.booruvault.ui.common.browserStart
import io.github.firebirks.booruvault.ui.common.LocalContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.UUID

const val NEW_SOURCE = "new"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesScreen(onBack: () -> Unit, onEdit: (String) -> Unit) {
    val repo = LocalContainer.current.settings
    val settings by repo.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    val sources = settings.sources

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sources_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.action_back)) } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(Icons.Outlined.Add, null) }, text = { Text(stringResource(R.string.sources_add)) })
        },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            items(sources, key = { it.id }) { source ->
                ListItem(
                    headlineContent = { Text(source.name) },
                    supportingContent = {
                        Text(
                            listOfNotNull(
                                stringResource(source.engine.labelRes()),
                                source.baseUrl.removePrefix("https://"),
                                if (source.hasCredentials) stringResource(R.string.sources_with_account) else null,
                            ).joinToString(" · "),
                        )
                    },
                    leadingContent = {
                        if (source.id == settings.selectedSource?.id) Icon(Icons.Outlined.CheckCircle, stringResource(R.string.sources_in_use), tint = MaterialTheme.colorScheme.primary)
                        else if (source.hasCredentials) Icon(Icons.Outlined.Key, null)
                    },
                    trailingContent = {
                        IconButton(onClick = { onEdit(source.id) }) { Icon(Icons.Outlined.Edit, stringResource(R.string.sources_edit)) }
                    },
                    modifier = Modifier.clickable { scope.launch { repo.selectSource(source.id) } },
                )
                HorizontalDivider()
            }
        }
    }

    if (adding) {
        val available = Presets.all.filter { preset -> sources.none { it.id == preset.source.id } }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text(stringResource(R.string.sources_add_title)) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    available.forEach { preset ->
                        ListItem(
                            headlineContent = { Text(preset.source.name) },
                            supportingContent = {
                                Text(listOfNotNull(stringResource(preset.source.engine.labelRes()), preset.note?.let { stringResource(it.labelRes()) }).joinToString(" · "))
                            },
                            modifier = Modifier.clickable {
                                adding = false
                                scope.launch { repo.setSources(sources + preset.source) }
                            },
                        )
                    }
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.sources_custom)) },
                        supportingContent = { Text(stringResource(R.string.sources_custom_hint)) },
                        leadingContent = { Icon(Icons.Outlined.Add, null) },
                        modifier = Modifier.clickable {
                            adding = false
                            onEdit(NEW_SOURCE)
                        },
                    )
                }
            },
            confirmButton = { TextButton(onClick = { adding = false }) { Text(stringResource(R.string.action_close)) } },
        )
    }
}

/** Labels of the two account fields and a hint, or null for engines that use no API account. */
private fun credentialLabels(engine: BooruEngine): Triple<Int, Int, Int>? = when (engine) {
    BooruEngine.DANBOORU -> Triple(R.string.source_user_name, R.string.source_api_key, R.string.source_hint_account_danbooru)
    BooruEngine.MOEBOORU -> Triple(R.string.source_user_name, R.string.source_password, R.string.source_hint_account_moebooru)
    BooruEngine.GELBOORU -> Triple(R.string.source_user_id, R.string.source_api_key, R.string.source_hint_account_gelbooru)
    BooruEngine.E621 -> Triple(R.string.source_user_name, R.string.source_api_key, R.string.source_hint_account_e621)
    BooruEngine.GELBOORU_HTML, BooruEngine.SHIMMIE, BooruEngine.HTML_GENERIC -> null
}

private fun addressHint(engine: BooruEngine): Int = when (engine) {
    BooruEngine.GELBOORU_HTML -> R.string.source_hint_gelbooru_html
    BooruEngine.SHIMMIE -> R.string.source_hint_shimmie
    BooruEngine.HTML_GENERIC -> R.string.source_hint_generic
    else -> R.string.source_hint_default
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceEditor(id: String?, onDone: () -> Unit) {
    val container = LocalContainer.current
    val repo = container.settings
    val settings by repo.settings.collectAsStateWithLifecycle()
    val existing = id?.let { key -> settings.sources.firstOrNull { it.id == key } }
    val scope = rememberCoroutineScope()
    // Plain remember throughout: a saved-state bundle would carry the site and the account name
    // out of the vault, into the system's activity record.
    var name by remember(existing?.id) { mutableStateOf(existing?.name ?: "") }
    var engine by remember(existing?.id) { mutableStateOf(existing?.engine ?: BooruEngine.DANBOORU) }
    var url by remember(existing?.id) { mutableStateOf(existing?.baseUrl ?: "https://") }
    var login by remember(existing?.id) { mutableStateOf(existing?.login ?: "") }
    // For Moebooru the stored key is a hash of the password; the field starts empty and a blank
    // entry keeps what is stored, so the hash never shows in a text field.
    var apiKey by remember(existing?.id) { mutableStateOf(if (existing?.engine == BooruEngine.MOEBOORU) "" else existing?.apiKey ?: "") }
    var engineMenu by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<UiText?>(null) }
    var testChallenge by remember { mutableStateOf<String?>(null) }
    val challenge = LocalChallenge.current
    var confirmDelete by remember { mutableStateOf(false) }
    val credentials = credentialLabels(engine)
    val valid = name.isNotBlank() && url.trim().matches(Regex("https?://[^\\s/]+.*"))

    fun key(): String = when {
        engine != BooruEngine.MOEBOORU -> apiKey.trim()
        apiKey.isNotBlank() -> MoebooruApi.passwordHash(apiKey)
        existing?.engine == BooruEngine.MOEBOORU -> existing.apiKey
        else -> ""
    }

    fun draft() = BooruSource(
        id = existing?.id ?: "custom-" + UUID.randomUUID().toString().take(8),
        name = name.trim(),
        engine = engine,
        baseUrl = url.trim().trimEnd('/'),
        login = login.trim(),
        apiKey = key(),
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(existing?.name ?: stringResource(R.string.source_new)) },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.action_back)) } },
                actions = {
                    if (existing != null) {
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.Delete, stringResource(R.string.action_delete)) }
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.source_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Column {
                OutlinedButton(onClick = { engineMenu = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.source_engine, stringResource(engine.labelRes())), Modifier.weight(1f))
                    Icon(Icons.Outlined.ArrowDropDown, null)
                }
                DropdownMenu(expanded = engineMenu, onDismissRequest = { engineMenu = false }) {
                    BooruEngine.entries.forEach {
                        DropdownMenuItem(text = { Text(stringResource(it.labelRes())) }, onClick = {
                            // A key or password typed for one engine means nothing to another, and
                            // for Moebooru it would be hashed as a password.
                            if (it != engine) apiKey = ""
                            engine = it
                            engineMenu = false
                        })
                    }
                }
            }
            // What the choice costs and gives: the API's complete posts, or the pages' independence.
            Text(
                stringResource(
                    when (engine) {
                        BooruEngine.GELBOORU -> R.string.source_mode_gelbooru
                        BooruEngine.GELBOORU_HTML, BooruEngine.SHIMMIE, BooruEngine.HTML_GENERIC -> R.string.source_mode_html
                        else -> R.string.source_mode_api
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                url, { url = it }, label = { Text(stringResource(R.string.source_address)) }, singleLine = true,
                supportingText = { Text(stringResource(addressHint(engine))) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
            if (credentials != null) {
                val (loginLabel, keyLabel, hint) = credentials
                Text(stringResource(R.string.source_account_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Text(stringResource(hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    login, { login = it }, label = { Text(stringResource(loginLabel)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                val keepsHash = engine == BooruEngine.MOEBOORU && existing?.engine == BooruEngine.MOEBOORU && existing.apiKey.isNotBlank()
                OutlinedTextField(
                    apiKey, { apiKey = it }, label = { Text(stringResource(keyLabel)) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    supportingText = if (keepsHash) ({ Text(stringResource(R.string.source_password_keep)) }) else null,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text(stringResource(R.string.source_site_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Text(
                stringResource(R.string.source_site_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(enabled = valid, onClick = {
                val draft = draft()
                challenge.open(draft, browserStart(draft), ChallengeMode.BROWSE) {
                    testResult = UiText.Res(R.string.source_cookies_saved)
                }
            }) { Text(stringResource(R.string.source_open_browser)) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(enabled = valid && !testing, onClick = {
                    testing = true
                    testResult = null
                    testChallenge = null
                    scope.launch {
                        testResult = try {
                            val posts = container.api(draft()).posts("", 1, 5)
                            UiText.Plural(R.plurals.source_test_ok, posts.size)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: BooruChallengeException) {
                            testChallenge = e.url
                            UiText.Of(e.problem)
                        } catch (e: Exception) {
                            UiText.Of(e.toProblem())
                        }
                        testing = false
                    }
                }) {
                    if (testing) CircularProgressIndicator(Modifier.padding(end = 8.dp).size(18.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.source_test))
                }
                Button(enabled = valid, onClick = {
                    val source = draft()
                    val list = if (existing == null) settings.sources + source else settings.sources.map { if (it.id == source.id) source else it }
                    scope.launch {
                        repo.setSources(list)
                        onDone()
                    }
                }) { Text(stringResource(R.string.action_save)) }
            }
            testResult?.let { Text(it.text(), style = MaterialTheme.typography.bodyMedium) }
            testChallenge?.let { url ->
                OutlinedButton(onClick = {
                    challenge.open(draft(), url) {
                        testChallenge = null
                        testResult = UiText.Res(R.string.source_check_done)
                    }
                }) { Text(stringResource(R.string.action_verify_in_browser)) }
            }
        }
    }

    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.source_remove_title, existing.name)) },
            text = { Text(stringResource(R.string.source_remove_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        repo.setSources(settings.sources.filterNot { it.id == existing.id })
                        onDone()
                    }
                }) { Text(stringResource(R.string.action_remove), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
