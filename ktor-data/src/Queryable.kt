package io.ktor.data

import io.ktor.data.Predicate.Everything

/**
 * Interface for querying entities that implement the [Identifiable] interface.
 *
 * @param E The type of entities being queried, constrained to types that implement [Identifiable].
 */
interface Queryable<out E> {
    /**
     * Get a selection of all entities.
     */
    fun all(): Selection<E> = find(Everything)

    /**
     * Get a selection of entities matching the given predicate.
     *
     * All match processing is deferred to the chained calls on the selection.
     *
     * @param predicate the criteria the rows must match
     */
    fun find(predicate: Predicate): Selection<E>
}

/**
 * Represents a selection of entities from a repository.
 *
 * The processing is deferred to the chained suspend calls for deriving the result list, page, updates or deletes.
 *
 * @param E the expected entity type
 */
interface Selection<out E> {
    suspend fun list(): List<E>
    suspend fun page(limit: UInt? = null, offset: UInt? = null): Page<E>

    suspend fun patchAll(values: FieldValues)

    suspend fun deleteAll()

    suspend fun single(): E

    suspend fun count(): UInt
}

typealias FieldValues = Map<Field<*>, Any?>

suspend inline fun <E> Selection<E>.forEach(op: (E) -> Unit) =
    list().forEach(op)

/**
 * List wrapper for a limited view of a larger source of data.
 *
 * @property items The list of items in the current page
 * @property total The total number of items available for this view
 */
data class Page<out E>(
    val items: List<E>,
    val total: UInt,
): List<E> by items

/**
 * Wrap the current list as a page
 *
 * @param total The total number of items available for this view
 */
fun <E> List<E>.asPage(total: UInt = size.toUInt()) =
    Page(this, total)
