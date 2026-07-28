package io.ktor.data

/**
 * A generic repository interface for managing entities that implement the Identifiable interface.
 *
 * @param E The type of the entity to be managed. Must extend Identifiable with the given ID type.
 * @param ID The type of the identifier for the entities being managed.
 */
interface Repository<E, ID> : Queryable<E>, Lookup<ID, E> {
    suspend fun create(e: E): E
    suspend fun create(items: Iterable<E>)

    suspend fun update(e: E)
    suspend fun delete(id: ID)
}

/**
 * A generic interface for retrieving entities by their unique identifiers.
 *
 * @param ID The type of the unique identifier used to locate an entity.
 * @param E The type of the entity to be returned.
 */
interface Lookup<in ID, out E> {
    suspend fun get(id: ID): E?
}