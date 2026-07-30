package io.ktor.data.todo

import TodoItem
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun TodoListView(
    todoList: List<TodoItem>,
    onAdd: (String) -> Unit,
    onUpdate: (TodoItem, String) -> Unit,
    onDelete: (TodoItem) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Todo List",
            fontSize = 48.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(todoList, key = { it.id.toInt() }) { item ->
                TodoRow(
                    item = item,
                    onUpdate = { text -> onUpdate(item, text) },
                    onDelete = { onDelete(item) },
                )
            }
        }
        NewTodoRow(onAdd = onAdd)
        Spacer(modifier = Modifier.height(24.dp))
    }
}