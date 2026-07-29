package io.ktor.data

import io.ktor.data.Predicate.Everything

/**
 * Interface for querying entities.
 *
 * @param E The type of entities being queried, constrained to types that implement [Identifiable].
 */
interface EntitySource<out E> {
    /**
     * Get a selection of all entities.
     */
    fun all(): SearchResult<E> = find(Everything)

    /**
     * Get a selection of entities matching the given predicate.
     *
     * All match processing is deferred to the chained calls on the selection.
     *
     * @param predicate the criteria the rows must match
     */
    fun find(predicate: Predicate): SearchResult<E>
}

interface SearchResult<out E> {
    suspend fun list(): List<E>
    suspend fun page(limit: UInt? = null, offset: UInt? = null): Page<E>
}

/**
 * Represents a selection of entities from a repository.
 *
 * The processing is deferred to the chained suspend calls for deriving the result list, page, updates or deletes.
 *
 * @param E the expected entity type
 */
interface Selection<out E>: SearchResult<E> {
    suspend fun patchAll(values: FieldValues)

    suspend fun deleteAll()

    suspend fun single(): E

    suspend fun count(): UInt
}

typealias FieldValues = Map<Field<*>, Any?>


/**
 * List wrapper for a limited view of a larger source of data.
 *
 * @property total The total number of items available for this view
 */
interface Page<out E>: List<E> {
    val total: UInt
}

suspend inline fun <E> Selection<E>.forEach(op: (E) -> Unit) =
    list().forEach(op)

/**
 * Wrap the current list as a page
 *
 * @param total The total number of items available for this view
 */
fun <E> List<E>.asPage(total: UInt = size.toUInt()): Page<E> =
    object: List<E> by this, Page<E> {
        override val total: UInt get() = total
    }
