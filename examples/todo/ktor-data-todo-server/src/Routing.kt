package io.ktor.data.todo.server

import TodoItem
import io.ktor.data.*
import io.ktor.data.rest.server.*
import io.ktor.http.*
import io.ktor.openapi.*
import io.ktor.server.application.*
import io.ktor.server.plugins.swagger.*
import io.ktor.server.routing.*
import io.ktor.server.routing.openapi.*

fun Application.configureRouting(todos: ObservableRepository<TodoItem, UInt>) {
    routing {
        swaggerUI("/swagger") {
            source = OpenApiDocSource.Routing(contentType = ContentType.Application.Yaml)
            info = OpenApiInfo(
                title = "Todo API",
                version = "1.0.0"
            )
        }
        route("/api") {
            restEndpoint("/todos", todos)
        }
    }
}