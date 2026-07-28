package io.ktor.data.serialization

import io.ktor.data.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule

/**
 * Encoder that writes a value into a flat in-memory shape consumed by [CopyAndReplaceIdDecoder]:
 *
 *  - The root is a [MutableMap] keyed by class property name, so [Predicate] predicates and
 *    repository column lookups can address top-level fields by name.
 *  - Every nested CLASS, LIST or MAP is encoded as a [MutableList] of values in the order
 *    the framework emits them — for maps that is alternating key, value pairs.
 */
@OptIn(ExperimentalSerializationApi::class)
internal class PropertiesEncoder private constructor(
    private val root: MutableMap<Field<*>, Any?>?,
    private val nested: MutableList<Any?>?,
    override val serializersModule: SerializersModule,
): AbstractEncoder() {
    constructor(
        root: MutableMap<Field<*>, Any?>,
        serializersModule: SerializersModule = EmptySerializersModule(),
    ) : this(root = root, nested = null, serializersModule)

    private var key: Field<*>? = null
    private var started: Boolean = false

    override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder {
        if (!started) {
            // Reuse this encoder for the root structure so the root map keeps receiving named fields.
            started = true
            return this
        }
        val child = mutableListOf<Any?>()
        write(child)
        return PropertiesEncoder(root = null, nested = child, serializersModule)
            .also { it.started = true }
    }

    override fun encodeElement(descriptor: SerialDescriptor, index: Int): Boolean {
        // Only the root map writer uses the name; nested list writers just append.
        key = Field<Any?>(descriptor.getElementName(index), type = null)
        return true
    }

    override fun encodeNull() { write(null) }
    override fun encodeValue(value: Any) { write(value) }

    private fun write(value: Any?) {
        if (root != null) {
            root[requireNotNull(key) { "Key must be set before encoding a root value" }] = value
        } else {
            nested!! += value
        }
    }
}
