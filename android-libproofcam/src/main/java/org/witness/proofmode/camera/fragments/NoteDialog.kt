package org.witness.proofmode.camera.fragments

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import org.witness.proofmode.ProofMode
import org.witness.proofmode.camera.R

@Composable
fun NoteDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }
    var noteText by remember {
        mutableStateOf(prefs.getString(ProofMode.PREF_CAWG_NOTES, "") ?: "")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = stringResource(R.string.settings_note))
        },
        text = {
            Column {
                Text(stringResource(R.string.note_dialog_explanation),
                    Modifier.padding(vertical = 8.dp))
                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.edit_note)) }
                )
            }
        },
        confirmButton = {
            Row {
                if (noteText.isNotEmpty()) {
                    TextButton(onClick = {
                        prefs.edit { remove(ProofMode.PREF_CAWG_NOTES) }
                        onDismiss()
                    }) {
                        Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }

                Button(onClick = {
                    if (noteText.isBlank()) {
                        prefs.edit { remove(ProofMode.PREF_CAWG_NOTES) }
                    } else {
                        prefs.edit { putString(ProofMode.PREF_CAWG_NOTES, noteText.trim()) }
                    }
                    onDismiss()
                }) {
                    Text(stringResource(R.string.action_save))
                }
            }
        },
        containerColor = CameraSurface,
        titleContentColor = ColorPrimary,
        textContentColor = MaterialTheme.colorScheme.onSurface
    )
}
