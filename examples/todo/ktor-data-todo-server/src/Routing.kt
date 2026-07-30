package io.ktor.data.todo.server

import TodoItem
import io.ktor.data.*
import io.ktor.data.rest.server.restEndpoint
import io.ktor.http.*
import io.ktor.openapi.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.plugins.swagger.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.routing.openapi.*
import io.ktor.utils.io.*

fun Application.configureRouting(todos: Repository<TodoItem, UInt>) {
    routing {
        swaggerUI("/swagger") {
            source = OpenApiDocSource.Routing(contentType = ContentType.Application.Yaml)
            info = OpenApiInfo(
                title = "Todo API",
                version = "1.0.0"
            )
        }
        route("/api") {
            install(ContentNegotiation) {
                json()
            }
            install(StatusPages) {
                exception<BadRequestException> { call, cause ->
                    call.respond(HttpStatusCode.BadRequest, cause.message ?: "Invalid request")
                }
                exception<Throwable> { call, cause ->
                    call.respond(HttpStatusCode.InternalServerError, cause.message ?: "Unknown error")
                }
            }
            restEndpoint("/todos", todos)
        }
    }
}