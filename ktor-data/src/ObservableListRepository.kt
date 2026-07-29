package io.ktor.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ObservableListRepository<E: Identifiable<ID>, ID>(
    private var list: List<E> = listOf(),
    private val nextId: () -> ID,
    private val withNewId: (E, ID) -> E,
    private val toBooleanFunction: Predicate.() -> (E) -> Boolean,
    private val toMappingFunction: FieldValues.() -> (E) -> E
): ObservableRepository<E, ID> {
    init {
        require(list.distinctBy { it.id }.size == list.size) { "All elements must have a unique id" }
    }
    private val updateMutex = Mutex()
    private val sharedFlow = MutableSharedFlow<ChangeEvent<E>>()

    override fun find(predicate: Predicate): ObservableSelection<E, ID> =
        ObservableListSelection(predicate.toBooleanFunction())

    override suspend fun create(e: E) {
        createAndGet(e)
    }

    override suspend fun createAndGet(e: E): E =
        updateMutex.withLock {
            withNewId(e, nextId()).also {
                list += it
                sharedFlow.emit(ChangeEvent.Created(it))
            }
        }

    override suspend fun createAll(items: Collection<E>) {
        updateMutex.withLock {
            val updatedList = items.map { withNewId(it, nextId()) }
            list += updatedList
            for (e in updatedList)
                sharedFlow.emit(ChangeEvent.Created(e))
        }
    }

    override suspend fun update(e: E) {
        updateMutex.withLock {
            list = list.map {
                if (it.id == e.id) {
                    sharedFlow.emit(ChangeEvent.Updated(e))
                    e
                }
                else it
            }
        }
    }

    override suspend fun updateAndGet(e: E): E {
        update(e)
        return e
    }

    override suspend fun updateAll(items: Collection<E>) {
        updateMutex.withLock {
            list = list.map { item ->
                items.find { it.id == item.id }?.also {
                    sharedFlow.emit(ChangeEvent.Updated(it))
                } ?: item
            }
        }
    }

    override suspend fun delete(id: ID) {
        updateMutex.withLock {
            list = list.filterNot {
                if (it.id == id) {
                    sharedFlow.emit(ChangeEvent.Deleted(id, it))
                    true
                } else false
            }
        }
    }

    override suspend fun get(id: ID): E? =
        list.find { it.id == id }

    internal inner class ObservableListSelection(val predicate: (E) -> Boolean): ObservableSelection<E, ID> {
        override suspend fun list() = list.filter(predicate)

        override suspend fun page(limit: UInt?, offset: UInt?): Page<E> {
            val matches = list.filter(predicate)
            val afterOffset = if (offset != null) matches.drop(offset.toInt()) else matches
            val items = if (limit != null) afterOffset.take(limit.toInt()) else afterOffset
            return items.asPage(total = matches.size.toUInt())
        }

        override suspend fun patchAll(values: FieldValues) {
            updateMutex.withLock {
                val mappingFunction = values.toMappingFunction()
                list = list.map { e ->
                    if (predicate(e)) {
                        mappingFunction(e).also {
                            sharedFlow.emit(ChangeEvent.Updated(e))
                        }
                    } else e
                }
            }
        }

        override suspend fun deleteAll() {
            updateMutex.withLock {
                list = list.filterNot { e ->
                    if (predicate(e)) {
                        sharedFlow.emit(ChangeEvent.Deleted(e.id, e))
                        true
                    } else false
                }
            }
        }

        override suspend fun single(): E =
            list.single(predicate)

        override suspend fun count(): UInt =
            list.count(predicate).toUInt()

        override suspend fun changeFlow(): Flow<ChangeEvent<E>> =
            when(predicate) {
                Predicate.Everything -> sharedFlow.asSharedFlow()
                Predicate.Nothing -> emptyFlow()
                else -> sharedFlow.asSharedFlow().filter { it.entity?.let(predicate) ?: true }
            }

    }
}