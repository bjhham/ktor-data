package io.ktor.data

/**
 * Represents an entity that can be uniquely identified by an ID.
 *
 * @param ID The type of the identifier used to uniquely distinguish instances.
 * @property id The unique identifier for this instance.
 */
interface Identifiable<ID> {
    val id: ID
}
