package io.ktor.data.serialization

import io.ktor.data.*
import kotlinx.serialization.SerializationException
import kotlinx.serialization.serializer

/**
 * Create a new ListRepository from a list of serializable elements.
 */
inline fun <reified E: Identifiable<UInt>> ObservableListRepository(list: List<E> = emptyList()): ObservableRepository<E, UInt> {
    require(list.all { it.id > 0u }) { "All elements must have a positive id" }
    return ObservableListRepository(list, nextUIntId(list))
}

/**
 * Create a new ListRepository from a list of serializable elements.
 */
inline fun <reified E: Identifiable<ID>, ID> ObservableListRepository(list: List<E> = emptyList(), noinline nextId: () -> ID): ObservableRepository<E, ID> {
    val serializer = try {
        serializer<E>()
    } catch (e: SerializationException) {
        throw IllegalArgumentException("Repository(List) expects @Serializable classes", e)
    }
    return ObservableListRepository(
        list = list.toMutableList(),
        nextId = nextId,
        withNewId = { e: E, id: ID ->
            serializer.deserialize(
                CopyAndReplaceIdDecoder(
                    source = e.toMap(serializer),
                    id = id,
                )
            )
        },
        toBooleanFunction = {
            val pairsPredicate = toAssignmentsBooleanFunction()
            ({ e: E -> pairsPredicate(e.toMap(serializer)) })
        },
        toMappingFunction = {
            { e: E ->
                serializer.deserialize(
                    CopyAndReplacePropertiesDecoder(
                        source = e.toMap(serializer),
                        replacements = this,
                    )
                )
            }
        },
    )
}