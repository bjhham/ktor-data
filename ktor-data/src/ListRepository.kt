package io.ktor.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * ListRepository is an in-memory implementation of the Repository interface using a MutableList as the underlying storage.
 *
 * @param E The type of elements stored in the repository. These elements must implement the Identifiable interface.
 * @param ID The type used for the unique identifier of the elements.
 * @property list The underlying list that stores the elements.
 * @property nextId A function that generates the next unique identifier.
 * @property withNewId A function that associates a new identifier with an element.
 * @property toBooleanFunction A function that converts a Predicate into a filter function for elements.
 * @property toMappingFunction A function for handling generic assignments for updates
 *
 * The ListRepository class ensures that the elements have unique IDs and provides CRUD operations including
 * create, read, update, and delete, along with list capabilities using predicates for querying data.
 *
 * This repository is suited for scenarios where an in-memory collection suffices or for testing purposes.
 */
class ListRepository<E: Identifiable<ID>, ID>(
    private var list: List<E> = listOf(),
    private val nextId: () -> ID,
    private val withNewId: (E, ID) -> E,
    private val toBooleanFunction: Predicate.() -> (E) -> Boolean,
    private val toMappingFunction: FieldValues.() -> (E) -> E
): Repository<E, ID> {
    init {
        require(list.distinctBy { it.id }.size == list.size) { "All elements must have a unique id" }
    }
    private val updateMutex = Mutex()

    override fun where(predicate: Predicate): Selection<E> =
        ListSelection(predicate.toBooleanFunction())

    override suspend fun create(e: E): E =
        withNewId(e, nextId()).also {
            list += it
        }

    override suspend fun create(items: Iterable<E>) {
        for (it in items)
            create(it)
    }

    override suspend fun update(e: E) {
        updateMutex.withLock {
            list = list.map {
                if (it.id == e.id) e else it
            }
        }
    }

    override suspend fun delete(id: ID) {
        updateMutex.withLock {
            list = list.filter {
                it.id != id
            }
        }
    }

    override suspend fun get(id: ID): E? =
        list.find { it.id == id }

    internal inner class ListSelection(val predicate: (E) -> Boolean): Selection<E> {
        override suspend fun list() = list.filter(predicate)

        override suspend fun page(limit: UInt?, offset: UInt?): Page<E> {
            val matches = list.filter(predicate)
            val afterOffset = if (offset != null) matches.drop(offset.toInt()) else matches
            val items = if (limit != null) afterOffset.take(limit.toInt()) else afterOffset
            return items.asPage(total = matches.size.toUInt())
        }

        override suspend fun updateAll(values: FieldValues) {
            updateMutex.withLock {
                list = list.map(values.toMappingFunction())
            }
        }

        override suspend fun deleteAll() {
            updateMutex.withLock {
                list = list.filterNot(predicate)
            }
        }

        override suspend fun single(): E =
            list.single(predicate)
    }
}

