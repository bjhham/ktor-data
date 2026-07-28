package io.ktor.data.serialization

import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import io.ktor.data.*
import kotlinx.coroutines.test.runTest
import kotlin.collections.emptyList

class ListRepositoryTest {

    private val name = Field<String>("name")

    @Test
    fun `basic CRUD`() = runTest {
        val examples = ListRepository<Example>()
        val first = examples.create(Example(name = "First"))
        val second = examples.create(Example(name = "Second"))
        assertEquals(listOf(first, second), examples.all().list())
        val updated = first.copy(name = "Updated")
        examples.update(updated)
        assertEquals(updated, examples.get(first.id))
        examples.delete(second.id)
        assertEquals(listOf(updated), examples.all().list())
    }

    @Test
    fun `nested objects`() = runTest {
        val nestedObjects = ListRepository<Nested>()
        val first = nestedObjects.create(Nested(
            examples = listOf(Example(name = "First")),
            attributes = mapOf("key" to "value")
        ))
        val second = nestedObjects.create(Nested(
            examples = listOf(Example(name = "Second")),
            attributes = mapOf("key" to "value")
        ))
        assertEquals(listOf(first, second), nestedObjects.all().list())
        val updated = first.copy(attributes = mapOf("key" to "updated"))
        nestedObjects.update(updated)
        assertEquals(updated, nestedObjects.get(first.id))
        nestedObjects.delete(second.id)
        assertEquals(listOf(updated), nestedObjects.all().list())
    }

    @Test
    fun querying() = runTest {
        val examples = ListRepository<Example>()
        examples.create(listOf(
            Example(name = "First"),
            Example(name = "Second"),
            Example(name = "Third"),
            Example(name = "Fourth"),
        ))
        assertEquals(3u, examples.where(name.isEqualTo("Third")).single().id)
        assertEquals(2u, examples.where(name.isOneOf("First", "Second") and name.isEqualTo("Second")).single().id)
        assertEquals(listOf(1u, 4u), examples.where(name.isEqualTo("First") or name.isEqualTo("Fourth")).list().map { it.id })
        assertEquals(listOf(1u, 2u), examples.where(name.isOneOf("First", "Second")).list().map { it.id })
    }

    @Test
    fun paging() = runTest {
        val examples = ListRepository<Example>()
        examples.create(listOf(
            Example(name = "First"),
            Example(name = "Second"),
            Example(name = "Third"),
            Example(name = "Fourth"),
        ))

        val all = examples.all().page()
        assertEquals(listOf(1u, 2u, 3u, 4u), all.map { it.id })
        assertEquals(4u, all.total)

        val firstTwo = examples.all().page(limit = 2u)
        assertEquals(listOf(1u, 2u), firstTwo.map { it.id })
        assertEquals(4u, firstTwo.total)

        val capped = examples.all().page(limit = 10u)
        assertEquals(listOf(1u, 2u, 3u, 4u), capped.map { it.id })
        assertEquals(4u, capped.total)

        val filtered = examples.where(name.isOneOf("First", "Second", "Third")).page(limit = 2u)
        assertEquals(listOf(1u, 2u), filtered.map { it.id })
        assertEquals(3u, filtered.total)

        val none = examples.all().page(limit = 0u)
        assertEquals(emptyList(), none.items)
        assertEquals(4u, none.total)
    }

}

@Serializable
data class Example(
    override val id: UInt = 0u,
    val name: String
): Identifiable<UInt>

@Serializable
data class Nested(
    override val id: UInt = 0u,
    val examples: List<Example>,
    val attributes: Map<String, String>
): Identifiable<UInt>