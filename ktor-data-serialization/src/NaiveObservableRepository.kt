package io.ktor.data.serialization

import io.ktor.data.Identifiable
import io.ktor.data.NaiveObservableRepository
import io.ktor.data.ObservableRepository
import io.ktor.data.Repository
import kotlinx.serialization.SerializationException
import kotlinx.serialization.serializer

inline fun <reified E: Identifiable<ID>, ID> NaiveObservableRepository(
    repository: Repository<E, ID>
): ObservableRepository<E, ID> {
    val serializer = try {
        serializer<E>()
    } catch (e: SerializationException) {
        throw IllegalArgumentException("Repository(List) expects @Serializable classes", e)
    }
    return NaiveObservableRepository(
        base = repository,
        toBooleanFunction = {
            val pairsPredicate = toAssignmentsBooleanFunction()
            ({ e: E -> pairsPredicate(e.toMap(serializer)) })
        }
    )
}