package dev.stackward.ui.security

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import dev.stackward.R
import dev.stackward.crypto.PasswordKeyCrypto
import dev.stackward.security.AppUnlock
import dev.stackward.security.PasswordRequest

/** Shows the key-password dialog whenever [unlock] asks for one (unlock, or choose a new password). */
@Composable
fun UnlockPasswordHost(unlock: AppUnlock) {
    val request by unlock.request.collectAsState()
    request?.let { PasswordDialog(it) }
}

@Composable
private fun PasswordDialog(request: PasswordRequest) {
    var password by remember(request) { mutableStateOf("") }
    var repeat by remember(request) { mutableStateOf("") }
    val create = request.mode == PasswordRequest.Mode.CREATE
    val tooShort = create && password.isNotEmpty() && password.length < PasswordKeyCrypto.MIN_PASSWORD_LENGTH
    val mismatch = create && repeat.isNotEmpty() && password != repeat
    val valid = if (create) {
        password.length >= PasswordKeyCrypto.MIN_PASSWORD_LENGTH && password == repeat
    } else {
        password.isNotEmpty()
    }

    AlertDialog(
        onDismissRequest = request::cancel,
        title = {
            Text(stringResource(if (create) R.string.pw_create_title else R.string.pw_unlock_title))
        },
        text = {
            Column {
                Text(stringResource(if (create) R.string.pw_create_body else R.string.pw_unlock_body))
                if (request.wrong) {
                    Text(
                        stringResource(R.string.pw_wrong),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.pw_field)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    supportingText = if (tooShort) {
                        { Text(stringResource(R.string.pw_too_short, PasswordKeyCrypto.MIN_PASSWORD_LENGTH)) }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                if (create) {
                    OutlinedTextField(
                        value = repeat,
                        onValueChange = { repeat = it },
                        label = { Text(stringResource(R.string.pw_repeat)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        supportingText = if (mismatch) {
                            { Text(stringResource(R.string.pw_mismatch)) }
                        } else {
                            null
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { request.submit(password.toCharArray()) }, enabled = valid) {
                Text(stringResource(if (create) R.string.pw_confirm_create else R.string.pw_confirm_unlock))
            }
        },
        dismissButton = {
            TextButton(onClick = request::cancel) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
