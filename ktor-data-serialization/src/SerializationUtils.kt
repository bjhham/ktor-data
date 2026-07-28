package io.ktor.data.serialization

import io.ktor.data.*
import io.ktor.data.Predicate.*
import kotlinx.serialization.KSerializer


/**
 * Converts the current object to a map representation using the provided serializer.
 *
 * @receiver The object to be converted into a map representation.
 * @param serializer The serializer used to convert the object into its map representation.
 * @return A map where keys are property names and values are their corresponding serialized values.
 */
fun <E> E.toMap(serializer: KSerializer<E>): Map<Field<*>, Any?> {
    val map = mutableMapOf<Field<*>, Any?>()
    serializer.serialize(
        PropertiesEncoder(map),
        this
    )
    return map
}

/**
 * Transforms a Predicate into a function that can evaluate a given map of key-value pairs.
 *
 * @receiver The Predicate to be transformed into a map evaluation function.
 * @return A function that takes a map of key-value pairs and returns true if the map satisfies the provided Predicate, or false otherwise.
 */
fun Predicate.toAssignmentsBooleanFunction(): (FieldValues) -> Boolean =
    when(this) {
        Everything -> { _ -> true }
        Nothing -> { _ -> false }
        is FieldClause -> { map -> map.entries.any { (key, value) -> test(key, value) } }
        is And -> clauses.map(Predicate::toAssignmentsBooleanFunction).let { predicates -> { list -> predicates.all { it(list) } } }
        is Or -> clauses.map(Predicate::toAssignmentsBooleanFunction).let { predicates -> { list -> predicates.any { it(list) } } }
    }