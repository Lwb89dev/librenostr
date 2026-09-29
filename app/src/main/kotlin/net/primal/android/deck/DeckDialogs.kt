package net.primal.android.deck

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import net.primal.android.core.compose.PrimalDefaults

@Composable
fun DeckNameDialog(
    title: String,
    initialName: String = "",
    onDismissRequest: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                colors = PrimalDefaults.outlinedTextFieldColors(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) {
                Text("Salva")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) { Text("Annulla") }
        },
    )
}

@Composable
fun DeleteDeckConfirmationDialog(
    deckName: String,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text("Eliminare \"$deckName\"?") },
        text = { Text("Verranno rimosse anche tutte le sue colonne.") },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Elimina") }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) { Text("Annulla") }
        },
    )
}
