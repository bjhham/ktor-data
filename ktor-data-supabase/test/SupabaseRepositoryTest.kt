package io.ktor.data.supabase

import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.data.Field
import io.ktor.data.Identifiable
import io.ktor.data.Predicate
import io.ktor.data.and
import io.ktor.data.contains
import io.ktor.data.isEqualTo
import io.ktor.data.isOneOf
import io.ktor.data.or
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies the PostgREST requests [SupabaseRepository] builds — the predicate translation, the
 * paging parameters and the row bodies — against a mock engine, so no live project is needed.
 */
class SupabaseRepositoryTest {

    @Serializable
    data class Example(
        override val id: UInt = 0u,
        val name: String,
        val tags: List<String> = emptyList(),
    ): Identifiable<UInt>

    private val name = Field<String>("name")
    private val tags = Field<Collection<String>>("tags")

    private val requests = mutableListOf<HttpRequestData>()
    private val request get() = requests.single()

    /**
     * A repository answering every request with [body], and reporting [total] as the row count
     * when one was requested.
     */
    private fun repository(body: String = "[]", total: Int? = null): SupabaseRepository<Example, UInt> {
        val engine = MockEngine { data ->
            requests += data
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()),
                    "Content-Range" to listOfNotNull(total?.let { "0-${it - 1}/$it" }),
                ),
            )
        }
        val client = createSupabaseClient("https://example.supabase.co", "test-key") {
            httpEngine = engine
            install(Postgrest)
        }
        return SupabaseRepository(client, "examples")
    }

    /** The request body, unwrapped from the array PostgREST inserts take. */
    private val HttpRequestData.jsonBody: JsonObject
        get() = when (val element = Json.parseToJsonElement((body as TextContent).text)) {
            is JsonArray -> element.single().jsonObject
            else -> element.jsonObject
        }

    @Test
    fun `get filters on the id column`() = runTest {
        val examples = repository("""[{"id":7,"name":"First","tags":[]}]""")
        assertEquals(Example(7u, "First"), examples.get(7u))
        assertEquals(HttpMethod.Get, request.method)
        assertEquals("eq.7", request.url.parameters["id"])
        assertEquals("1", request.url.parameters["limit"])
    }

    @Test
    fun `get returns null when nothing matches`() = runTest {
        assertNull(repository("[]").get(7u))
    }

    @Test
    fun `createAndGet omits the generated id and returns the stored row`() = runTest {
        val examples = repository("""[{"id":1,"name":"First","tags":["a"]}]""")
        val created = examples.createAndGet(Example(name = "First", tags = listOf("a")))
        assertEquals(Example(1u, "First", listOf("a")), created)
        assertEquals(HttpMethod.Post, request.method)
        assertEquals(setOf("name", "tags"), request.jsonBody.keys)
        assertTrue(request.headers["Prefer"]!!.contains("return=representation"))
    }

    @Test
    fun `create sends a client-side id when the database does not generate it`() = runTest {
        val engine = MockEngine { data ->
            requests += data
            respond("[]", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }
        val client = createSupabaseClient("https://example.supabase.co", "test-key") {
            httpEngine = engine
            install(Postgrest)
        }
        val examples = SupabaseRepository<Example, UInt>(client, "examples", idGeneratedByDatabase = false)
        examples.create(Example(9u, "First"))
        assertEquals(setOf("id", "name", "tags"), request.jsonBody.keys)
    }

    @Test
    fun `update patches every column of the row it matches by id`() = runTest {
        val examples = repository()
        examples.update(Example(3u, "Updated"))
        assertEquals(HttpMethod.Patch, request.method)
        assertEquals("eq.3", request.url.parameters["id"])
        // `encodeDefaults` keeps properties that happen to equal their default in the body.
        assertEquals(setOf("id", "name", "tags"), request.jsonBody.keys)
    }

    @Test
    fun `delete matches by id`() = runTest {
        repository().delete(3u)
        assertEquals(HttpMethod.Delete, request.method)
        assertEquals("eq.3", request.url.parameters["id"])
    }

    @Test
    fun `equality and membership become column filters`() = runTest {
        repository().find(name.isEqualTo("First")).list()
        assertEquals("eq.First", request.url.parameters["name"])

        requests.clear()
        repository().find(name.isOneOf("First", "Second")).list()
        assertEquals("in.(First,Second)", request.url.parameters["name"])
    }

    @Test
    fun `string and collection containment become like and cs filters`() = runTest {
        repository().find(name.contains("irs")).list()
        assertEquals("like.%irs%", request.url.parameters["name"])

        requests.clear()
        repository().find(tags.contains(listOf("a", "b"))).list()
        assertEquals("cs.{a,b}", request.url.parameters["tags"])
    }

    @Test
    fun `logical groupings nest`() = runTest {
        repository().find(name.isEqualTo("First") or name.isEqualTo("Second")).list()
        assertEquals("(name.eq.First,name.eq.Second)", request.url.parameters["or"])

        requests.clear()
        repository().find(name.isOneOf("First", "Second") and name.isEqualTo("Second")).list()
        assertEquals("(name.in.(First,Second),name.eq.Second)", request.url.parameters["and"])
    }

    @Test
    fun `Everything is sent unfiltered`() = runTest {
        repository().all().list()
        assertEquals(listOf("select"), request.url.parameters.names().toList())
    }

    @Test
    fun `Nothing resolves without a request`() = runTest {
        val examples = repository()
        val nothing = examples.find(Predicate.Nothing)
        assertEquals(emptyList(), nothing.list())
        assertEquals(0u, nothing.count())
        assertEquals(0u, nothing.page().total)
        nothing.deleteAll()
        nothing.patchAll(mapOf(name to "Ignored"))
        assertEquals(emptyList(), requests.toList())
    }

    @Test
    fun `page requests a range and reports the total`() = runTest {
        val examples = repository("""[{"id":3,"name":"Third","tags":[]}]""", total = 9)
        val page = examples.all().page(limit = 1u, offset = 2u)
        assertEquals(listOf(3u), page.map { it.id })
        assertEquals(9u, page.total)
        assertEquals("2", request.url.parameters["offset"])
        assertEquals("1", request.url.parameters["limit"])
        assertTrue(request.headers["Prefer"]!!.contains("count=exact"))
    }

    @Test
    fun `page with an offset alone does not bound the limit`() = runTest {
        repository(total = 9).all().page(offset = 2u)
        assertEquals("2", request.url.parameters["offset"])
        assertNull(request.url.parameters["limit"])
    }

    @Test
    fun `page with a zero limit only counts`() = runTest {
        val page = repository(total = 4).all().page(limit = 0u)
        assertEquals(emptyList(), page.toList())
        assertEquals(4u, page.total)
    }

    @Test
    fun `count reads the total from the content range`() = runTest {
        assertEquals(4u, repository(total = 4).all().count())
        assertEquals("id", request.url.parameters["select"])
        assertTrue(request.headers["Prefer"]!!.contains("count=exact"))
    }

    @Test
    fun `patchAll writes the given fields to every matching row`() = runTest {
        repository().find(name.isEqualTo("First")).patchAll(mapOf(name to "Updated", tags to listOf("a")))
        assertEquals(HttpMethod.Patch, request.method)
        assertEquals("eq.First", request.url.parameters["name"])
        assertEquals("""{"name":"Updated","tags":["a"]}""", (request.body as TextContent).text)
    }

    @Test
    fun `deleteAll deletes every matching row`() = runTest {
        repository().find(name.isEqualTo("First")).deleteAll()
        assertEquals(HttpMethod.Delete, request.method)
        assertEquals("eq.First", request.url.parameters["name"])
    }

    @Test
    fun `single fetches two rows to reject an ambiguous match`() = runTest {
        val examples = repository("""[{"id":1,"name":"First","tags":[]}]""")
        assertEquals(1u, examples.find(name.isEqualTo("First")).single().id)
        assertEquals("2", request.url.parameters["limit"])
    }

    @Test
    fun `updateAll updates each item`() = runTest {
        val examples = repository()
        examples.updateAll(listOf(Example(1u, "First"), Example(2u, "Second")))
        assertEquals(listOf("eq.1", "eq.2"), requests.map { it.url.parameters["id"] })
    }

    @Test
    fun `createAll inserts in a single request`() = runTest {
        val examples = repository()
        examples.createAll(listOf(Example(name = "First"), Example(name = "Second")))
        assertEquals(HttpMethod.Post, request.method)
        assertEquals(
            """[{"name":"First","tags":[]},{"name":"Second","tags":[]}]""",
            (request.body as TextContent).text,
        )
    }

    @Test
    fun `createAll of nothing does not call out`() = runTest {
        repository().createAll(emptyList())
        assertEquals(emptyList(), requests.toList())
    }
}
