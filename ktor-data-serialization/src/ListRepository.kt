package io.ktor.data.serialization

import io.ktor.data.*
import kotlinx.serialization.*
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.AbstractDecoder
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch

/**
 * Create a new Repository from a list of serializable elements.  Useful short-hand for testing.
 */
inline fun <reified E: Identifiable<UInt>> ListRepository(list: List<E> = emptyList()): Repository<E, UInt> {
    require(list.all { it.id > 0u }) { "All elements must have a positive id" }
    val serializer = try {
        serializer<E>()
    } catch (e: SerializationException) {
        throw IllegalArgumentException("Repository(List) expects @Serializable classes", e)
    }
    return ListRepository(
        list = list.toMutableList(),
        nextId = nextUIntId(list),
        withNewId = { e: E, id: UInt ->
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

/**
 * Build a `nextId` generator for `UInt` ids that starts at `max(existing) + 1`.
 */
@OptIn(ExperimentalAtomicApi::class)
fun <E: Identifiable<UInt>> nextUIntId(list: List<E>): () -> UInt {
    val counter = AtomicLong(list.maxOfOrNull { it.id.toLong() } ?: 0L)
    return { counter.incrementAndFetch().toUInt() }
}

/**
 * Decoder that reconstructs a value previously written by [PropertiesEncoder].
 *
 * The encoder writes the root as a [Map] keyed by property name and every nested structure
 * (class, list, or map) as a flat [List] of values in descriptor order — for maps that means
 * alternating key, value pairs. Decoding is therefore a sequential walk over an [Iterator]:
 * each `decodeElementIndex` returns the next index and stages the next value, and we are
 * done as soon as the iterator is exhausted.
 */
@OptIn(ExperimentalSerializationApi::class)
open class PropertiesDecoder(
    private val values: Iterator<Any?>,
    override val serializersModule: SerializersModule = EmptySerializersModule(),
): AbstractDecoder() {

    constructor(
        source: Map<Field<*>, Any?>,
        serializersModule: SerializersModule = EmptySerializersModule(),
    ) : this(source.values.iterator(), serializersModule)

    private var index = 0
    private var pending: Any? = null

    override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder {
        val nested = pending ?: return this
        pending = null
        @Suppress("UNCHECKED_CAST")
        return PropertiesDecoder((nested as List<Any?>).iterator(), serializersModule)
    }

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        if (!values.hasNext()) return CompositeDecoder.DECODE_DONE
        pending = nextValue(descriptor, index, values.next())
        return index++
    }

    /**
     * Hook for subclasses to transform the raw value about to be staged for element [index]
     * of [descriptor]. The default implementation returns [raw] unchanged.
     */
    protected open fun nextValue(descriptor: SerialDescriptor, index: Int, raw: Any?): Any? = raw

    override fun decodeValue(): Any = pending!!.also { pending = null }

    override fun decodeNull(): kotlin.Nothing? = null.also { pending = null }

    override fun decodeNotNullMark(): Boolean = pending != null
}

/**
 * A [PropertiesDecoder] that replaces the top-level `id` property with the supplied [id].
 *
 * Only the root structure is affected: nested structures are decoded by a plain
 * [PropertiesDecoder] (see [PropertiesDecoder.beginStructure]).
 *
 * Note: when [ID] is [UInt] the staged value is converted to [Int] so that the framework's
 * `decodeInt` path can consume it (mirroring how [PropertiesEncoder] writes `UInt` ids).
 */
class CopyAndReplaceIdDecoder<ID>(
    source: Map<Field<*>, Any?>,
    private val id: ID,
    serializersModule: SerializersModule = EmptySerializersModule(),
): PropertiesDecoder(source, serializersModule) {
    override fun nextValue(descriptor: SerialDescriptor, index: Int, raw: Any?): Any? =
        when(descriptor.getElementName(index)) {
            "id" -> if (id is UInt) id.toInt() else id
            else -> raw
        }
}

/**
 * A [PropertiesDecoder] that replaces an arbitrary set of top-level properties in [source]
 * with the supplied [replacements] (keyed by serialized property name).
 *
 * Only the root structure is affected: nested structures (the replacements themselves,
 * which are typically `List<*>` for join-loaded relations) are decoded by a plain
 * [PropertiesDecoder] just like any other nested value.
 */
class CopyAndReplacePropertiesDecoder(
    source: Map<Field<*>, Any?>,
    replacements: FieldValues,
    serializersModule: SerializersModule = EmptySerializersModule(),
): PropertiesDecoder(
    source = source.toMutableMap().apply { putAll(replacements) },
    serializersModule = serializersModule,
)
