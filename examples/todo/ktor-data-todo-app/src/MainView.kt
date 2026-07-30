package io.ktor.data.todo

import TodoItem
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.ktor.data.*
import kotlinx.coroutines.launch

@Composable
fun MainView(todoRepo: ObservableRepository<TodoItem, UInt>) {
    val scope = rememberCoroutineScope()
    val todoList by todoRepo.all().listFlow().collectAsStateWithLifecycle(initialValue = null)

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            val currentList = todoList
            if (currentList == null) {
                LoadingView()
            } else {
                TodoListView(
                    todoList = currentList,
                    onAdd = { text ->
                        scope.launch {
                            todoRepo.create(TodoItem(id = 0u, text = text))
                        }
                    },
                    onUpdate = { item, text ->
                        scope.launch {
                            todoRepo.update(item.copy(text = text))
                        }
                    },
                    onDelete = { item ->
                        scope.launch {
                            todoRepo.delete(item.id)
                        }
                    },
                )
            }
        }
    }
}

@Composable
fun LoadingView() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

