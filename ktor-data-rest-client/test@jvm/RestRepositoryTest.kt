package io.ktor.data.rest

import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientCN
import io.ktor.server.sse.SSE as ServerSSE
import io.ktor.client.plugins.sse.SSE as ClientSSE
import io.ktor.data.*
import io.ktor.data.rest.server.*
import io.ktor.data.serialization.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerCN
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.sse.*
import io.ktor.server.testing.*
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RestRepositoryTest {

    @Test
    fun `basic CRUD`() = runTest {
        val events = mutableListOf<ChangeEvent<Example>>()
        lateinit var watchJob: Job

        testApplication {
            installRestEndpoint()

            val examples = RestRepository<Example, UInt>("/examples", client.config {
                install(ClientSSE)
                install(ClientCN) {
                    json()
                }
            })
            val changeFlow = examples.all().changeFlow()
            watchJob = application.launch(start = CoroutineStart.UNDISPATCHED) {
                changeFlow.collect { events += it }
            }
            yield()
            val first = examples.createAndGet(Example(name = "First"))
            val second = examples.createAndGet(Example(name = "Second"))
            assertEquals(listOf(first, second), examples.all().list())

            val updated = first.copy(name = "Updated")
            examples.update(updated)
            assertEquals(updated, examples.get(first.id))

            examples.delete(second.id)
            assertEquals(listOf(updated), examples.all().list())
        }
        watchJob.join()

        assertTrue(events.getOrNull(0) is ChangeEvent.Created, "Created should be first")
        assertTrue(events.getOrNull(1) is ChangeEvent.Created, "Created should be second")
        assertTrue(events.getOrNull(2) is ChangeEvent.Updated, "Updated should be third")
        assertTrue(events.getOrNull(3) is ChangeEvent.Deleted, "Deleted should be fourth")
    }

    private fun ApplicationTestBuilder.installRestEndpoint() {
        install(ServerSSE)
        install(StatusPages) {
            exception<BadRequestException> { call, cause ->
                call.respond(HttpStatusCode.BadRequest, cause.message ?: "Invalid request")
            }
            exception<Throwable> { call, cause ->
                call.respond(HttpStatusCode.InternalServerError, cause.message ?: "Unknown error")
            }
        }
        install(ServerCN) {
            json()
        }
        routing {
            restEndpoint("/examples", ObservableListRepository<Example>())
        }
    }

}

@Serializable
data class Example(
    override val id: UInt = 0u,
    val name: String
): Identifiable<UInt>