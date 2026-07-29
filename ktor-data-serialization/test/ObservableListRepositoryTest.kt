package io.ktor.data.serialization

import io.ktor.data.*
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals

class ObservableListRepositoryTest {

    private val name = Field<String>("name")

    @Test
    fun `basic CRUD`() = runTest {
        val examples = ObservableListRepository<Example>()
        val events = mutableListOf<ChangeEvent<Example>>()
        val watchJob = launch(start = CoroutineStart.UNDISPATCHED) {
            examples.all().changeFlow().collect { events += it }
        }
        val first = examples.createAndGet(Example(name = "First"))
        val second = examples.createAndGet(Example(name = "Second"))
        assertEquals(listOf(first, second), examples.all().list())

        val updated = first.copy(name = "Updated")
        examples.update(updated)
        assertEquals(updated, examples.get(first.id))

        examples.delete(second.id)
        assertEquals(listOf(updated), examples.all().list())

        yield()
        watchJob.cancel()
        assertEquals(listOf(
            ChangeEvent.Created(first),
            ChangeEvent.Created(second),
            ChangeEvent.Updated(updated),
            ChangeEvent.Deleted(second.id, second),
        ), events)
    }

    @Test
    fun `nested objects`() = runTest {
        val nestedObjects = ObservableListRepository<Nested>()
        val first = nestedObjects.createAndGet(Nested(
            examples = listOf(Example(name = "First")),
            attributes = mapOf("key" to "value")
        ))
        val second = nestedObjects.createAndGet(Nested(
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
        val examples = ObservableListRepository<Example>()
        examples.createAll(listOf(
            Example(name = "First"),
            Example(name = "Second"),
            Example(name = "Third"),
            Example(name = "Fourth"),
        ))
        assertEquals(3u, examples.find(name.isEqualTo("Third")).single().id)
        assertEquals(2u, examples.find(name.isOneOf("First", "Second") and name.isEqualTo("Second")).single().id)
        assertEquals(listOf(1u, 4u), examples.find(name.isEqualTo("First") or name.isEqualTo("Fourth")).list().map { it.id })
        assertEquals(listOf(1u, 2u), examples.find(name.isOneOf("First", "Second")).list().map { it.id })
    }

    @Test
    fun paging() = runTest {
        val examples = ObservableListRepository<Example>()
        examples.createAll(listOf(
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

        val filtered = examples.find(name.isOneOf("First", "Second", "Third")).page(limit = 2u)
        assertEquals(listOf(1u, 2u), filtered.map { it.id })
        assertEquals(3u, filtered.total)

        val none = examples.all().page(limit = 0u)
        assertEquals(emptyList(), none.toList())
        assertEquals(4u, none.total)
    }

    @Test
    fun `createAll assigns correct ids and publishes events`() = runTest {
        val examples = ObservableListRepository<Example>()
        val events = mutableListOf<ChangeEvent<Example>>()
        val watchJob = launch(start = CoroutineStart.UNDISPATCHED) {
            examples.all().changeFlow().collect { events += it }
        }
        examples.createAll(listOf(
            Example(name = "First"),
            Example(name = "Second"),
        ))
        val all = examples.all().list()
        assertEquals(2, all.size)
        assertEquals(1u, all[0].id)
        assertEquals("First", all[0].name)
        assertEquals(2u, all[1].id)
        assertEquals("Second", all[1].name)

        yield()
        watchJob.cancel()
        assertEquals(listOf<ChangeEvent<Example>>(
            ChangeEvent.Created(all[0]),
            ChangeEvent.Created(all[1]),
        ), events)
    }

    @Test
    fun `updateAll updates correct items and publishes events`() = runTest {
        val examples = ObservableListRepository<Example>()
        val first = examples.createAndGet(Example(name = "First"))
        val second = examples.createAndGet(Example(name = "Second"))

        val events = mutableListOf<ChangeEvent<Example>>()
        val watchJob = launch(start = CoroutineStart.UNDISPATCHED) {
            examples.all().changeFlow().collect { events += it }
        }

        val updatedFirst = first.copy(name = "Updated First")
        val updatedSecond = second.copy(name = "Updated Second")
        examples.updateAll(listOf(updatedFirst, updatedSecond))

        assertEquals(updatedFirst, examples.get(first.id))
        assertEquals(updatedSecond, examples.get(second.id))

        yield()
        watchJob.cancel()
        assertEquals(listOf<ChangeEvent<Example>>(
            ChangeEvent.Updated(updatedFirst),
            ChangeEvent.Updated(updatedSecond),
        ), events)
    }

    @Test
    fun `listFlow reflects changes`() = runTest {
        val examples = ObservableListRepository<Example>()
        val first = examples.createAndGet(Example(name = "First"))

        val emissions = mutableListOf<List<Example>>()
        val collectJob = launch(start = CoroutineStart.UNDISPATCHED) {
            examples.all().listFlow().collect { emissions += it }
        }

        val second = examples.createAndGet(Example(name = "Second"))
        val updated = first.copy(name = "Updated")
        examples.update(updated)
        examples.delete(second.id)

        yield()
        collectJob.cancel()
        assertEquals(4, emissions.size)
        assertEquals(listOf(first), emissions[0])                // initial
        assertEquals(listOf(first, second), emissions[1])        // after create
        assertEquals(listOf(updated, second), emissions[2])      // after update
        assertEquals(listOf(updated), emissions[3])              // after delete
    }

    @Test
    fun `pageFlow reflects changes`() = runTest {
        val examples = ObservableListRepository<Example>()
        val first = examples.createAndGet(Example(name = "First"))

        val emissions = mutableListOf<Page<Example>>()
        val collectJob = launch(start = CoroutineStart.UNDISPATCHED) {
            examples.all().pageFlow().collect { emissions += it }
        }

        val second = examples.createAndGet(Example(name = "Second"))
        val updated = first.copy(name = "Updated")
        examples.update(updated)
        examples.delete(second.id)

        yield()
        collectJob.cancel()
        assertEquals(4, emissions.size)

        assertEquals(listOf(first), emissions[0].toList())
        assertEquals(1u, emissions[0].total)

        assertEquals(listOf(first, second), emissions[1].toList())
        assertEquals(2u, emissions[1].total)

        assertEquals(listOf(updated, second), emissions[2].toList())
        assertEquals(2u, emissions[2].total)

        assertEquals(listOf(updated), emissions[3].toList())
        assertEquals(1u, emissions[3].total)
    }

}
