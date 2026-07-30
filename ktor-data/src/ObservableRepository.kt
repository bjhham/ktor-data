package io.ktor.data

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

interface ObservableRepository<E: Identifiable<ID>, ID>: Repository<E, ID> {

    override fun all(): ObservableSelection<E, ID> = find(Predicate.Everything)
    
    override fun find(predicate: Predicate): ObservableSelection<E, ID>
}

interface ObservableSelection<out E: Identifiable<ID>, ID>: Selection<E> {
    fun listFlow(): Flow<List<E>> = flow {
        var current = list()
        emit(current)
        changeFlow().collect { change ->
            current = when (change) {
                is ChangeEvent.Created<E> -> current + change.entity
                is ChangeEvent.Updated<E> -> current.map { if (it.id == change.entity.id) change.entity else it }
                is ChangeEvent.Deleted<E, *> -> current.filterNot { it.id == change.id }
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
                is ChangeEvent.Created<E> -> (current + change.entity).asPage(total = currentTotal.incrementAndGet().toUInt())
                is ChangeEvent.Updated<E> -> current.map { if (it.id == change.entity.id) change.entity else it }.asPage(total = currentTotal.value.toUInt())
                is ChangeEvent.Deleted<E, *> -> current.filterNot { it.id == change.id }.asPage(total = currentTotal.decrementAndGet().toUInt())
            }
            emit(current)
        }
    }
    suspend fun changeFlow(): Flow<ChangeEvent<E>>
}

sealed interface ChangeEvent<out E> {
    val entity: E? get() = null

    data class Created<E>(override val entity: E) : ChangeEvent<E>
    data class Updated<E>(override val entity: E) : ChangeEvent<E>
    data class Deleted<E, ID>(val id: ID, override val entity: E?) : ChangeEvent<E>
}