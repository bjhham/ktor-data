package io.ktor.data.rest.server

import io.ktor.data.*
import io.ktor.data.Predicate.*
import io.ktor.http.*
import io.ktor.openapi.*
import io.ktor.server.application.log
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.routing.openapi.*
import io.ktor.server.sse.heartbeat
import io.ktor.server.sse.sse
import io.ktor.server.util.*
import io.ktor.sse.ServerSentEvent
import io.ktor.utils.io.*
import kotlinx.serialization.json.Json
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.full.isSuperclassOf
import kotlin.reflect.jvm.jvmErasure
import kotlin.time.Duration.Companion.seconds

const val DEFAULT_PAGE_SIZE = 50u
val PAGINATION_PARAM_NAMES = setOf("limit", "offset")

@OptIn(ExperimentalKtorApi::class)
inline fun <reified E: Identifiable<ID>, reified ID: Any> Route.restEndpoint(
    path: String,
    repository: Repository<E, ID>,
) {
    val entityClass = E::class
    val entityNameSingular = entityClass.simpleName!!.replace(Regex("([a-z])([A-Z])")) {
        "${it.groupValues[1]} ${it.groupValues[2].lowercase()}"
    }
    val entityTag = entityNameSingular + "s"
    // We consider basic types only to be searchable
    val searchableProperties = entityClass.declaredMemberProperties.filter {
        !Collection::class.isSuperclassOf(it.returnType.jvmErasure)
    }

    route(path) {
        post {
            call.respond(repository.createAndGet(call.receive<E>()))
        }.describe {
            summary = "Create a new $entityNameSingular"
            tag(entityTag)
            requestBody {
                schema = jsonSchema<E>()
            }
            responses {
                HttpStatusCode.OK {
                    description = "Created $entityNameSingular"
                    schema = jsonSchema<E>()
                }
            }
        }

        get {
            val queryParameters = call.request.queryParameters
            val limit = queryParameters["limit"]?.toUIntOrNull()
            val offset = queryParameters["offset"]?.toUIntOrNull()

            val query = queryParameters.entries().filter {
                it.key !in PAGINATION_PARAM_NAMES
            }.map { (key, values) ->
                when (val value = values.singleOrNull()) {
                    null -> OneOf(Field<Any?>(key), values)
                    else -> Equals(Field<Any?>(key), value)
                }
            }.takeIf { it.isNotEmpty() }?.reduce(Predicate::and) ?: Everything

            val result = repository.find(query).page(limit = limit, offset = offset)
            call.response.headers.append(HttpHeaders.XTotalCount, result.total.toString())
            call.respond(result.toList())
        }.describe {
            summary = "List all $entityTag"
            tag(entityTag)
            parameters {
                for (property in searchableProperties) {
                    query(property.name) {
                        description = "Filter by ${property.name}"
                    }
                }
                query("offset") {
                    description = "Index for starting results"
                    schema = jsonSchema<UInt>()
                }
                query("limit") {
                    description = "Max results (default: $DEFAULT_PAGE_SIZE)"
                    schema = jsonSchema<UInt>().copy(
                        default = GenericElement(DEFAULT_PAGE_SIZE)
                    )
                }
            }
            responses {
                HttpStatusCode.OK {
                    description = "List of $entityTag"
                    schema = jsonSchema<List<E>>()
                }
            }
        }

        route("/{id}") {

            get {
                val id: ID by call.pathParameters
                val entity = repository.get(id) ?: call.respond(HttpStatusCode.NotFound)
                call.respond(entity)
            }.describe {
                summary = "Get an existing $entityNameSingular"
                tag(entityTag)
                parameters {
                    path("id") {
                        description = "ID of the $entityNameSingular to retrieve"
                        schema = jsonSchema<ID>()
                    }
                }
            }

            put {
                val entity = call.receive<E>()
                require(call.parameters["id"]?.toUInt() == entity.id) { "ID in path and body must match" }
                repository.update(entity)
                call.respond(HttpStatusCode.NoContent)
            }.describe {
                summary = "Update an existing $entityNameSingular"
                tag(entityTag)
                parameters {
                    path("id") {
                        description = "ID of the $entityNameSingular to delete"
                        schema = jsonSchema<Int>()
                    }
                }
                requestBody {
                    schema = jsonSchema<E>()
                }
                responses {
                    HttpStatusCode.NoContent {
                        description = "Updated $entityNameSingular"
                    }
                }
            }

            delete {
                val id: ID by call.pathParameters
                repository.delete(id)
                call.respond(HttpStatusCode.NoContent)
            }.describe {
                summary = "Delete an existing $entityNameSingular"
                tag(entityTag)
                parameters {
                    path("id") {
                        description = "ID of the $entityNameSingular to delete"
                        schema = jsonSchema<ID>()
                    }
                }
                responses {
                    HttpStatusCode.NoContent {
                        description = "Deleted $entityNameSingular"
                    }
                }
            }
        }

        // TODO predicates, custom serialization
        (repository as? ObservableRepository<E, ID>)?.let {
            val json = Json.Default
            sse("/events") {
                heartbeat {
                    period = 1.seconds
                }
                repository.all().changeFlow().collect { event ->
                    try {
                        val data = json.encodeToString(event)
                        send(ServerSentEvent(data))
                    } catch (e: Exception) {
                        application.log.error("Failed to send event", e)
                    }
                }
                application.log.info("SSE connection ended unexpectedly")
            }
        }
    }
}
