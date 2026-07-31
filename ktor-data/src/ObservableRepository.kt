package io.ktor.data

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable

interface ObservableRepository<E : Identifiable<ID>, ID> : Repository<E, ID> {
    override fun all(): ObservableSelection<E, ID> = find(Predicate.Everything)

    override fun find(predicate: Predicate): ObservableSelection<E, ID>
}

interface ObservableSelection<out E : Identifiable<ID>, ID> : Selection<E> {
    fun listFlow(): Flow<List<E>> = flow {
        var current = list()
        emit(current)
        changeFlow().collect { change ->
            current = when (change) {
                is ChangeEvent.Created<E> -> current + change.entity
                is ChangeEvent.Updated<E> -> current.map { if (it.id == change.entity.id) change.entity else it }
                is ChangeEvent.Deleted<E> -> current.filterNot { it.id == change.entity.id }
            }
            emit(current)
        }
    }

    fun pageFlow(limit: UInt? = null, offset: UInt? = null): Flow<Page<E>> = flow {
        var current = page(limit, offset)
        val currentTotal = atomic(current.total.toLong())
        emit(current)
        changeFlow().collect { change ->
            current = when (change) {
                is ChangeEvent.Created<E> -> (current + change.entity).asPage(
                    total = currentTotal.incrementAndGet().toUInt()
                )

                is ChangeEvent.Updated<E> -> current.map { if (it.id == change.entity.id) change.entity else it }
                    .asPage(total = currentTotal.value.toUInt())

                is ChangeEvent.Deleted<E> -> current.filterNot { it.id == change.entity.id }
                    .asPage(total = currentTotal.decrementAndGet().toUInt())
            }
            emit(current)
        }
    }

    suspend fun changeFlow(): Flow<ChangeEvent<E>>
}

@Serializable
sealed interface ChangeEvent<out E> {
    val entity: E
    @Serializable
    data class Created<E>(override val entity: E) : ChangeEvent<E>
    @Serializable
    data class Updated<E>(override val entity: E) : ChangeEvent<E>
    @Serializable
    data class Deleted<E>(override val entity: E) : ChangeEvent<E>
}

class NaiveObservableRepository<E : Identifiable<ID>, ID>(
    private val base: Repository<E, ID>,
    private val toBooleanFunction: Predicate.() -> (E) -> Boolean,
) : Repository<E, ID> by base, ObservableRepository<E, ID> {

    private val sharedFlow = MutableSharedFlow<ChangeEvent<E>>()

    override suspend fun createAndGet(e: E): E =
        base.createAndGet(e).also {
            sharedFlow.emit(ChangeEvent.Created(it))
        }

    override suspend fun updateAndGet(e: E): E =
        base.updateAndGet(e).also {
            sharedFlow.emit(ChangeEvent.Updated(it))
        }

    override suspend fun create(e: E) {
        base.createAndGet(e).also {
            sharedFlow.emit(ChangeEvent.Created(it))
        }
    }

    // generated IDs will not be available here
    override suspend fun createAll(items: Collection<E>) =
        base.createAll(items).also {
            for (item in items)
                sharedFlow.emit(ChangeEvent.Created(item))
        }

    override suspend fun update(e: E) =
        base.update(e).also {
            sharedFlow.emit(ChangeEvent.Updated(e))
        }

    override suspend fun updateAll(items: Collection<E>) =
        base.updateAll(items).also {
            for (item in items)
                sharedFlow.emit(ChangeEvent.Updated(item))
        }

    override suspend fun delete(id: ID) {
        get(id)?.let { e ->
            base.delete(id)
            sharedFlow.emit(ChangeEvent.Deleted(e))
        }
    }

    override fun all(): ObservableSelection<E, ID> = find(Predicate.Everything)

    override fun find(predicate: Predicate): ObservableSelection<E, ID> {
        val baseResult = base.find(predicate)
        val filter = predicate.toBooleanFunction()
        return object : Selection<E> by baseResult, ObservableSelection<E, ID> {
            override suspend fun changeFlow(): Flow<ChangeEvent<E>> =
                when (predicate) {
                    Predicate.Everything -> sharedFlow.asSharedFlow()
                    Predicate.Nothing -> emptyFlow()
                    else -> sharedFlow.asSharedFlow().filter { filter(it.entity) }
                }

            override suspend fun patchAll(values: FieldValues) {
                baseResult.patchAll(values)
                base.find(predicate).forEach {
                    sharedFlow.emit(ChangeEvent.Updated(it))
                }
            }

            override suspend fun deleteAll() {
                val toDelete = base.find(predicate).list()
                baseResult.deleteAll()
                toDelete.forEach {
                    sharedFlow.emit(ChangeEvent.Deleted(it))
                }
            }
        }
    }
}