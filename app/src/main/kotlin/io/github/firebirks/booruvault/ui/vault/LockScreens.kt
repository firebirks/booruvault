package io.github.firebirks.booruvault.ui.vault

import kotlinx.coroutines.Dispatchers
import io.github.firebirks.booruvault.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import io.github.firebirks.booruvault.ui.common.LocalContainer
import io.github.firebirks.booruvault.ui.common.PasswordField
import io.github.firebirks.booruvault.ui.common.findActivity
import io.github.firebirks.booruvault.vault.BiometricKey
import io.github.firebirks.booruvault.vault.VaultHeaderException
import io.github.firebirks.booruvault.vault.VaultManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

const val MIN_PASSWORD = 8

/**
 * Asks for a fingerprint and stores a biometric copy of the vault key. [scope] must outlive the
 * prompt: a screen that closes when the prompt opens would cancel the storing with it.
 */
fun enrollBiometric(activity: FragmentActivity, vault: VaultManager, scope: CoroutineScope, onResult: (String) -> Unit) {
    val cipher = try {
        BiometricKey.encryptCipher()
    } catch (e: Exception) {
        onResult(activity.getString(R.string.biometric_key_failed, e.message.orEmpty()))
        return
    }
    BiometricKey.authenticate(activity, activity.getString(R.string.biometric_enable_title), cipher) { authorized, error ->
        when {
            authorized != null -> scope.launch(Dispatchers.Main) {
                runCatching { vault.enableBiometric(authorized) }
                    .onSuccess { onResult(activity.getString(R.string.biometric_enabled)) }
                    .onFailure { onResult(activity.getString(R.string.biometric_enable_failed, it.message.orEmpty())) }
            }
            error != null -> onResult(error)
        }
    }
}

@Composable
private fun LockLayout(content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.widthIn(max = 420.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) { content() }
        }
    }
}

@Composable
fun UnlockScreen(
    title: String = stringResource(R.string.unlock_title_vault),
    subtitle: String = stringResource(R.string.unlock_subtitle_vault),
) {
    val container = LocalContainer.current
    val vault = container.vault
    val context = LocalContext.current
    val activity = context.findActivity() as? FragmentActivity
    val scope = rememberCoroutineScope()
    // Deliberately not saveable: a password has no business in the saved-state bundle.
    var password by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var prompted by rememberSaveable { mutableStateOf(false) }
    val wrongPassword = stringResource(R.string.password_wrong)
    val headerMismatch = stringResource(R.string.vault_header_mismatch)
    val biometricChanged = stringResource(R.string.biometric_changed)
    val unlockFailed = stringResource(R.string.unlock_failed)
    val promptTitle = stringResource(R.string.biometric_unlock_title)
    val canBiometric = activity != null && vault.biometricEnabled && BiometricKey.isAvailable(context)

    fun unlockWithPassword() {
        if (password.isEmpty() || working) return
        working = true
        error = null
        scope.launch {
            val ok = try {
                vault.unlock(password.toCharArray())
            } catch (_: VaultHeaderException) {
                working = false
                error = headerMismatch
                return@launch
            }
            working = false
            if (ok) password = "" else error = wrongPassword
        }
    }

    fun unlockWithBiometric() {
        val iv = vault.biometricIv ?: return
        val cipher = BiometricKey.decryptCipher(iv)
        if (cipher == null || activity == null) {
            scope.launch { vault.disableBiometric() }
            error = biometricChanged
            return
        }
        BiometricKey.authenticate(activity, promptTitle, cipher) { authorized, message ->
            if (authorized != null) {
                working = true
                scope.launch {
                    try {
                        if (!vault.unlockWithBiometric(authorized)) error = unlockFailed
                    } catch (_: VaultHeaderException) {
                        error = headerMismatch
                    }
                    working = false
                }
            } else if (message != null) {
                error = message
            }
        }
    }

    LaunchedEffect(canBiometric) {
        if (canBiometric && !prompted) {
            prompted = true
            unlockWithBiometric()
        }
    }

    LockLayout {
        Icon(Icons.Outlined.Lock, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        PasswordField(
            value = password,
            onValueChange = {
                password = it
                error = null
            },
            label = stringResource(R.string.password),
            imeAction = ImeAction.Go,
            onDone = ::unlockWithPassword,
            isError = error != null,
            supportingText = error,
            enabled = !working,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = ::unlockWithPassword, enabled = password.isNotEmpty() && !working, modifier = Modifier.fillMaxWidth()) {
            if (working) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(10.dp))
                Text(stringResource(R.string.unlock_deriving))
            } else {
                Text(stringResource(R.string.unlock_action))
            }
        }
        if (canBiometric) {
            OutlinedButton(onClick = ::unlockWithBiometric, enabled = !working, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Fingerprint, null)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.unlock_fingerprint))
            }
        }
    }
}

@Composable
fun VaultSetupScreen(onCreated: () -> Unit) {
    val vault = LocalContainer.current.vault
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var understood by rememberSaveable { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    val tooShort = password.isNotEmpty() && password.length < MIN_PASSWORD
    val mismatch = confirm.isNotEmpty() && confirm != password
    val valid = password.length >= MIN_PASSWORD && password == confirm && understood

    fun create() {
        if (!valid || working) return
        working = true
        scope.launch {
            vault.create(password.toCharArray())
            password = ""
            confirm = ""
            working = false
            onCreated()
        }
    }

    LockLayout {
        Icon(Icons.Outlined.Shield, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(R.string.setup_text),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        PasswordField(
            value = password, onValueChange = { password = it }, label = stringResource(R.string.password),
            imeAction = ImeAction.Next, isError = tooShort,
            supportingText = if (tooShort) stringResource(R.string.password_too_short, MIN_PASSWORD) else null,
            enabled = !working, modifier = Modifier.fillMaxWidth(),
        )
        PasswordField(
            value = confirm, onValueChange = { confirm = it }, label = stringResource(R.string.password_confirm),
            onDone = ::create, isError = mismatch,
            supportingText = if (mismatch) stringResource(R.string.password_mismatch) else null,
            enabled = !working, modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = understood, onCheckedChange = { understood = it }, enabled = !working)
            Text(
                stringResource(R.string.setup_understood),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Button(onClick = ::create, enabled = valid && !working, modifier = Modifier.fillMaxWidth()) {
            if (working) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(10.dp))
                Text(stringResource(R.string.setup_creating))
            } else {
                Text(stringResource(R.string.setup_create))
            }
        }
    }
}
