package io.ktor.data.rest

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.data.FieldValues
import io.ktor.data.Identifiable
import io.ktor.data.Page
import io.ktor.data.Predicate
import io.ktor.data.Repository
import io.ktor.data.Selection
import io.ktor.data.asPage
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.util.reflect.TypeInfo
import io.ktor.util.reflect.typeInfo
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

inline fun <reified E : Identifiable<ID>, reified ID> RestRepository(
    path: String,
    client: HttpClient = HttpClient {
        this.install(ContentNegotiation) {
            json()
        }
    }
): RestRepository<E, ID> =
    RestRepository(
        path,
        client,
        typeInfo<E>(),
        typeInfo<List<E>>()
    )

class RestRepository<E : Identifiable<ID>, ID>(
    private val path: String,
    private val client: HttpClient,
    private val elementTypeInfo: TypeInfo,
    private val listTypeInfo: TypeInfo,
    private val setOffset: HttpRequestBuilder.(UInt) -> Unit = { offset -> url.parameters.append(OFFSET_PARAM, offset.toString()) },
    private val setLimit: HttpRequestBuilder.(UInt) -> Unit = { limit -> url.parameters.append(LIMIT_PARAM, limit.toString()) },
    private val setPredicate: HttpRequestBuilder.(Predicate) -> Unit = { predicate ->  },
    private val getTotalHeader: HttpResponse.() -> UInt = { headers[HttpHeaders.XTotalCount]?.toUInt() ?: 0u }
): Repository<E, ID> {
    companion object {
        const val TOTAL_HEADER = "X-Total-Count"
        const val OFFSET_PARAM = "offset"
        const val LIMIT_PARAM = "limit"
    }

    override suspend fun createAndGet(e: E): E =
        client.post(path) {
            setBody(e, elementTypeInfo)
        }.body(elementTypeInfo)

    override suspend fun updateAndGet(e: E): E =
        client.put(idPath(e.id)) {
            setBody(e, elementTypeInfo)
        }.body(elementTypeInfo)

    // TODO: predicates are not yet translated into server side filtering; every selection currently
    //  behaves as if it were built from Predicate.Everything.
    override fun find(predicate: Predicate): Selection<E> =
        RestClientSelection(predicate)

    override suspend fun get(id: ID): E? {
        val response = client.get(idPath(id)) { expectSuccess = false }
        if (response.status == HttpStatusCode.NotFound) return null
        return response.body(elementTypeInfo)
    }

    override suspend fun create(e: E) {
        client.post(path) {
            setBody(e, elementTypeInfo)
        }
    }

    override suspend fun createAll(items: Collection<E>) {
        for (e in items) create(e)
    }

    override suspend fun update(e: E) {
        client.put(idPath(e.id)) {
            setBody(e, elementTypeInfo)
        }
    }

    override suspend fun updateAll(items: Collection<E>) {
        for (e in items) update(e)
    }

    override suspend fun delete(id: ID) {
        client.delete(idPath(id))
    }

    private fun idPath(id: ID): String = "$path/$id"

    inner class RestClientSelection(private val predicate: Predicate): Selection<E> {
        override suspend fun patchAll(values: FieldValues) {
            if (values.isEmpty()) return
            val body = values.toJsonObject()
            for (e in list()) {
                client.patch(idPath(e.id)) {
                    setBody(body)
                }
            }
        }

        override suspend fun deleteAll() {
            for (e in list()) delete(e.id)
        }

        override suspend fun single(): E =
            list().single()

        override suspend fun count(): UInt =
            page(limit = 1u, offset = 0u).total

        override suspend fun list(): List<E> =
            client.get(path).body(listTypeInfo)

        override suspend fun page(limit: UInt?, offset: UInt?): Page<E> =
            client.get(path) {
                setPredicate(predicate)
                if (limit != null) setLimit(limit)
                if (offset != null) setOffset(offset)
            }.let { response ->
                response.body<Page<E>>(listTypeInfo)
                    .asPage(response.getTotalHeader())
            }
    }
}

private fun FieldValues.toJsonObject(): JsonObject =
    JsonObject(entries.associate { (field, value) -> field.name to value.toJsonElement() })

private fun Any?.toJsonElement(): JsonElement =
    when (this) {
        null -> JsonNull
        is JsonElement -> this
        is String -> JsonPrimitive(this)
        is Boolean -> JsonPrimitive(this)
        is Number -> JsonPrimitive(this)
        is Enum<*> -> JsonPrimitive(name)
        else -> JsonPrimitive(toString())
    }
