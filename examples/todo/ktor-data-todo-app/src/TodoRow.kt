package io.ktor.data.todo

import TodoItem
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun TodoRow(
    item: TodoItem,
    onUpdate: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var text by remember(item.id) { mutableStateOf(item.text) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            singleLine = true,
        )
        IconButton(onClick = { if (text != item.text) onUpdate(text) }) {
            Icon(imageVector = Icons.Filled.Check, contentDescription = "Save")
        }
        IconButton(onClick = onDelete) {
            Icon(imageVector = Icons.Filled.Delete, contentDescription = "Delete")
        }
    }
}