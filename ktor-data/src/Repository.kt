package io.ktor.data

/**
 * A generic repository interface for managing entities that implement the Identifiable interface.
 *
 * @param E The type of the entity to be managed. Must extend Identifiable with the given ID type.
 * @param ID The type of the identifier for the entities being managed.
 */
interface Repository<E, in ID> : EntitySource<E>, Lookup<ID, E>, EntitySink<E, ID> {
    /**
     * Create an entity and get a new copy with all automatically generated fields populated.
     *
     * @param e The entity to be created.
     * @return A new copy of the entity with all automatically generated fields populated.
     */
    suspend fun createAndGet(e: E): E

    /**
     * Update an entity and get a new copy with all automatically generated fields populated.
     *
     * @param e The entity to be updated.
     * @return A new copy of the entity with all automatically generated fields populated.
     */
    suspend fun updateAndGet(e: E): E

    /**
     * Provides a mutable selection of all entities in the repository.
     */
    override fun all(): Selection<E> = find(Predicate.Everything)

    /**
     * Provides a mutable selection of entities based on the given predicate.
     *
     * @param predicate The predicate used to filter entities.
     */
    override fun find(predicate: Predicate): Selection<E>

}

/**
 * A generic interface for creating and updating entities.
 *
 * @param E The type of the entity to be managed.
 */
interface EntitySink<in E, in ID> {
    suspend fun create(e: E)
    suspend fun createAll(items: Collection<E>)
    suspend fun update(e: E)
    suspend fun updateAll(items: Collection<E>)
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