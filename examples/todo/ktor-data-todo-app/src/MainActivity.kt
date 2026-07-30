package io.ktor.data.todo

import TodoItem
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import io.ktor.data.serialization.*

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val repository = ObservableListRepository<TodoItem>()
        setContent {
            MainView(repository)
        }
    }
}
