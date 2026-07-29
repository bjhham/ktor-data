package io.ktor.data

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

interface ObservableRepository<E: Identifiable<ID>, ID>: Repository<E, ID> {

    override fun all(): ObservableSelection<E, ID> = find(Predicate.Everything)
    
    override fun find(predicate: Predicate): ObservableSelection<E, ID>
}

interface ObservableSelection<out E: Identifiable<ID>, ID>: Selection<E> {
    suspend fun listFlow(): Flow<List<E>> = flow {
        val list = list()
        emit(list)
        emitAll(changeFlow().map { change ->
            when (change) {
                is ChangeEvent.Created<E> -> list + change.entity
                is ChangeEvent.Updated<E> -> list.map { if (it.id == change.entity.id) change.entity else it }
                is ChangeEvent.Deleted<E, *> -> list.filterNot { it.id == change.id }
            }
        })
    }
    suspend fun pageFlow(limit: UInt? = null, offset: UInt? = null): Flow<Page<E>> = flow {
        val page = page()
        val currentTotal = atomic(page.total.toLong())
        emit(page)
        emitAll(changeFlow().map { change ->
            when (change) {
                is ChangeEvent.Created<E> -> (page + change.entity).asPage(total = currentTotal.incrementAndGet().toUInt())
                is ChangeEvent.Updated<E> -> page.map { if (it.id == change.entity.id) change.entity else it }.asPage(total = currentTotal.value.toUInt())
                is ChangeEvent.Deleted<E, *> -> page.filterNot { it.id == change.id }.asPage(total = currentTotal.decrementAndGet().toUInt())
            }
        })
    }
    suspend fun changeFlow(): Flow<ChangeEvent<E>>
}

sealed interface ChangeEvent<out E> {
    val entity: E? get() = null

    data class Created<E>(override val entity: E) : ChangeEvent<E>
    data class Updated<E>(override val entity: E) : ChangeEvent<E>
    data class Deleted<E, ID>(val id: ID, override val entity: E?) : ChangeEvent<E>
}