package io.ktor.data.todo

import TodoItem
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import io.ktor.client.HttpClient
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.sse.SSE
import io.ktor.data.rest.RestRepository
import io.ktor.serialization.kotlinx.json.json

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val repository = RestRepository<TodoItem, UInt>("/api/todos", HttpClient {
            install(SSE)
            install(ContentNegotiation) {
                json()
            }
            install(Logging)
            install(DefaultRequest) {
                url("http://10.0.2.2:8080/")
            }
        })
        setContent {
            MainView(repository)
        }
    }
}
