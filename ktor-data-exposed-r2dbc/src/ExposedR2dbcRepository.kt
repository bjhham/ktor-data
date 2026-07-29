package io.ktor.data.exposed.r2dbc

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.AbstractDecoder
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.serializer
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IdTable
import org.jetbrains.exposed.v1.core.ops.SingleValueInListOp
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.r2dbc.*
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import kotlinx.datetime.LocalDateTime as KotlinLocalDateTime
import io.ktor.data.*
import io.ktor.data.Predicate.*
import io.ktor.data.serialization.*

/**
 * Create an [ExposedR2bcRepository] for a `@Serializable` entity, deriving
 * `rowToEntity`, `assignColumns`, `withId`, and `enrich` from its [KSerializer].
 *
 * Pass [relations] to populate nested collections (1-to-many / many-to-many) on the
 * entity in a single joined read; see [Relation], [OneToMany], [ManyToMany].
 */
inline fun <reified E: Identifiable<ID>, ID: Comparable<ID>> ExposedR2bcRepository(
    database: R2dbcDatabase,
    table: IdTable<ID>,
    relations: List<Relation<E>> = emptyList(),
): ExposedR2dbcRepository<E, ID> {
    val serializer = try {
        serializer<E>()
    } catch (e: SerializationException) {
        throw IllegalArgumentException("ExposedRepository(database, table) expects a @Serializable entity", e)
    }
    val inlineChildren = relations.filter { !it.isCollection }
    val baseAssign = assignColumnsFromSerializer<E, ID>(serializer)
    return ExposedR2dbcRepository(
        database = database,
        table = table,
        rowToEntity = { row: ResultRow ->
            serializer.deserialize(ResultRowDecoder(table, row, inlineChildren))
        },
        assignColumns = { e ->
            val base = baseAssign(e)
            val withRelations: IdTable<ID>.(UpdateBuilder<*>) -> Unit = { stmt ->
                base(stmt)
                for (relation in relations) {
                    relation.assignParentColumns(e, stmt)
                }
            }
            withRelations
        },
        withId = { id ->
            serializer.deserialize(
                CopyAndReplaceIdDecoder(
                    source = this.toMap(serializer).toMap(),
                    id = id,
                )
            )
        },
        relations = relations,
        enrich = { e: E, replacements: Map<String, Any?> ->
            serializer.deserialize(
                CopyAndReplacePropertiesDecoder(
                    source = e.toMap(serializer).toMap(),
                    replacements = replacements.mapKeys { Field<Any?>(it.key) },
                )
            )
        },
    )
}

/**
 * Build an `assignColumns` function from a [KSerializer], using
 * the serialized property names to look up columns on the table.
 */
fun <E, ID: Comparable<ID>> assignColumnsFromSerializer(
    serializer: KSerializer<E>,
): (E) -> IdTable<ID>.(UpdateBuilder<*>) -> Unit = { e ->
    val columnValues = e.toMap(serializer)
    val builder: IdTable<ID>.(UpdateBuilder<*>) -> Unit = { stmt ->
        for ((key, value) in columnValues) {
            if (key.name == "id") continue
            // Properties that don't map to a column on this table (e.g. join-loaded
            // collections like `skills`) are silently ignored here; persistence of those
            // is a separate concern handled by Relation write-side support.
            val column = columns.find { it.name == key.name } ?: continue
            @Suppress("UNCHECKED_CAST")
            val typedColumn = column as Column<Any?>
            stmt[typedColumn] = typedColumn.coerce(value).value
        }
    }
    builder
}

/**
 * A [kotlinx.serialization.encoding.Decoder] that reads values from an Exposed [ResultRow]
 * by mapping each element of the [SerialDescriptor] to a column on the given [IdTable].
 *
 * Properties whose serialized name doesn't correspond to a column on [table] but ARE provided
 * by an inline (non-collection) [Relation] in [inlineChildren] — i.e. a [OneToOne] — are decoded
 * inline from the joined row, so the entity's nested 1:1 field is populated directly without
 * needing an entity-level default.
 *
 * Other properties without a matching column (typically relation-backed collections like
 * `Vet.skills` that are populated from a join after the fact) are skipped; the framework will
 * then fall back to whatever default the entity declares for that field.
 */
@OptIn(ExperimentalSerializationApi::class)
class ResultRowDecoder<ID: Comparable<ID>>(
    private val table: IdTable<ID>,
    private val row: ResultRow,
    private val inlineChildren: List<Relation<*>> = emptyList(),
    override val serializersModule: SerializersModule = EmptySerializersModule(),
): AbstractDecoder() {
    private var elementIndex = 0
    private var currentColumn: Column<*>? = null
    private var pendingInlineChild: List<Any?>? = null

    override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder {
        val inline = pendingInlineChild
        if (inline != null) {
            pendingInlineChild = null
            return PropertiesDecoder(values = inline.iterator(), serializersModule = serializersModule)
        }
        return this
    }

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        while (elementIndex < descriptor.elementsCount) {
            val name = descriptor.getElementName(elementIndex)
            val column = table.columns.find { it.name == name }
            if (column != null) {
                currentColumn = column
                pendingInlineChild = null
                return elementIndex++
            }
            val inline = inlineChildren.firstOrNull { it.property == name }
            if (inline != null) {
                val encoded = inline.encodedChildFromRow(row)
                if (encoded != null) {
                    currentColumn = null
                    pendingInlineChild = encoded
                    return elementIndex++
                }
                // LEFT JOIN missed; fall through and let the entity default apply.
            }
            // No matching column or inline child: skip this property and let the entity's default apply.
            elementIndex++
        }
        return CompositeDecoder.DECODE_DONE
    }

    override fun decodeValue(): Any {
        val column = requireNotNull(currentColumn) { "No current element" }
        return when (val value = row[column]) {
            is EntityID<*> -> value.value
            else -> requireNotNull(value) { "Missing value for ${column.name}" }
        }
    }

    override fun decodeInt(): Int {
        return when(val value = decodeValue()) {
            is UInt -> value.toInt()
            else -> value as Int
        }
    }

    /**
     * Bridge between datetime-typed columns and string-serialized entity fields.
     *
     * `kotlin.time.Instant` (and other ISO-8601 string-coded types) ask the decoder for a
     * `String`, but a column declared with Exposed's `datetime(...)` produces a
     * `kotlinx.datetime.LocalDateTime`. We convert that local time back to an `Instant`
     * using [TimeZone.currentSystemDefault] — mirroring how `ColumnConversion.coerce`
     * persists it — so `Instant` (de)serialization round-trips cleanly.
     */
    override fun decodeString(): String =
        when (val value = decodeValue()) {
            is KotlinLocalDateTime -> value.toInstant(TimeZone.currentSystemDefault()).toString()
            else -> value as String
        }

    override fun decodeNull() = null

    override fun endStructure(descriptor: SerialDescriptor) {
        currentColumn = null
        pendingInlineChild = null
    }
}

/**
 * A generic repository implementation based on the Exposed SQL library. This class provides
 * mechanisms to perform CRUD operations and manage relational mappings for entities that
 * implement the `Identifiable` interface.
 *
 * @param ID The type of the identifier used to uniquely distinguish entities.
 * @param E The type of the entity managed by this repository.
 *
 * @property database The database instance used for executions.
 * @property table The table representation of the entity.
 * @property rowToEntity A mapper to convert database rows into entities.
 * @property assignColumns A function to assign properties from the entity to the database columns.
 * @property withId A function to retrieve the entity’s unique identifier.
 * @property relations A collection of `Relation` instances defining relational mappings.
 * @property enrich A function to enrich entities with additional data from relations.
 */
open class ExposedR2dbcRepository<E: Identifiable<ID>, ID: Comparable<ID>>(
    protected val database: R2dbcDatabase,
    protected val table: IdTable<ID>,
    protected val rowToEntity: (ResultRow) -> E,
    protected val assignColumns: (E) -> IdTable<ID>.(UpdateBuilder<*>) -> Unit,
    protected val withId: E.(ID) -> E,
    protected val relations: List<Relation<E>> = emptyList(),
    protected val enrich: (E, Map<String, Any?>) -> E = { e, _ -> e },
): Repository<E, ID> {

    /** The full `FROM` clause: the base table joined with every relation, in order. */
    protected val joinedSource: ColumnSet by lazy {
        relations.fold(table as ColumnSet) { acc, r -> r.join(acc) }
    }

    override suspend fun get(id: ID): E? =
        withTransaction {
            val rows = joinedSource.selectAll()
                .where { table.id eq id }
            foldRows(rows).singleOrNull()
        }

    override suspend fun create(e: E) {
        createAndGet(e)
    }

    override suspend fun createAndGet(e: E): E =
        withTransaction {
            val newId = table.insert(assignColumns(e))[table.id].value
            val withId = e.withId(newId)
            enrichWithPersistedRelations(withId, persistRelations(newId, withId))
        }

    override suspend fun createAll(items: Collection<E>) {
        withTransaction {
            for (e in items) {
                val newId = table.insert(assignColumns(e))[table.id].value
                val withId = e.withId(newId)
                enrichWithPersistedRelations(withId, persistRelations(newId, withId))
            }
        }
    }

    override fun find(predicate: Predicate): Selection<E> =
        ExposedSelection(predicate)

    /**
     * Count distinct parent entities matching [predicate], using `COUNT(DISTINCT table.id)`
     * over the joined source. This is robust against the row-fan-out introduced by collection
     * relations, so the reported [Page.total] is always the true number of base entities for
     * this view — independent of how many child rows each parent contributes to the join.
     */
    private suspend fun countDistinctParents(predicate: Predicate): UInt {
        val countExpr = table.id.countDistinct()
        val query = when (predicate) {
            Everything -> joinedSource.select(countExpr)
            else -> joinedSource.select(countExpr).where { toBooleanOp(predicate) }
        }
        val row = query.toList().single()
        return row[countExpr].toUInt()
    }

    /**
     * Stream-fold a flat (joined) flow of [ResultRow]s into one entity per distinct parent id,
     * collecting each collection relation's children into the matching property on the entity.
     *
     * Behaviour:
     *  - Parent order is the order they first appear in [rows].
     *  - Within each collection relation (1-to-many / many-to-many), child rows are
     *    de-duplicated by [Relation.childIdColumn] so that the cross-product introduced by
     *    joining several relations does not duplicate the children of any one of them.
     *  - A LEFT JOIN with no matching child row is detected by `childIdColumn` being null
     *    and contributes nothing.
     *  - Non-collection (1-to-1) relations are populated directly during [rowToEntity] from
     *    the joined row, so they do not participate in this fold.
     *  - When [limit] is non-null, collection consumes only as many rows as needed to emit
     *    [limit] distinct parents. The flow is short-circuited as soon as a row arrives for
     *    a (limit+1)-th parent — this avoids materializing the full joined result set for
     *    large tables. For this to be safe, callers must order rows by [table.id] so that
     *    every parent's row block is contiguous.
     *  - When [offset] is non-null, the first [offset] distinct parents (and their child
     *    rows) are skipped before collection begins. As with [limit], this relies on rows
     *    for one parent being contiguous.
     */
    protected suspend fun foldRows(rows: Flow<ResultRow>, limit: UInt? = null, offset: UInt? = null): List<E> {
        val collectionRelations = relations.filter { it.isCollection }
        if (collectionRelations.isEmpty()) return distinctRowsToEntities(rows, offset = offset)

        val limitInt = limit?.toInt()
        val offsetInt = offset?.toInt() ?: 0
        val parents = LinkedHashMap<ID, E>()
        // parentId -> property -> childId -> encoded child row (List<Any?>)
        val collected = LinkedHashMap<ID, MutableMap<String, LinkedHashMap<Any, List<Any?>>>>()
        val skippedParents = HashSet<ID>()
        var seenParentCount = 0

        try {
            rows.collect { row ->
                val parentId = row[table.id].value
                if (parentId in skippedParents) return@collect
                if (parentId !in parents) {
                    if (seenParentCount < offsetInt) {
                        // Still within the offset window: record this parent as skipped so
                        // subsequent rows for it (children from the join fan-out) are ignored.
                        skippedParents += parentId
                        seenParentCount++
                        return@collect
                    }
                    if (limitInt != null && parents.size >= limitInt) {
                        // A new parent past the limit — every prior parent's children have
                        // already been collected (rows for one parent are contiguous), so we
                        // can stop streaming entirely without reading any more rows.
                        throw FoldComplete()
                    }
                    parents[parentId] = rowToEntity(row)
                    seenParentCount++
                }
                val byProperty = collected.getOrPut(parentId) { mutableMapOf() }
                for (relation in collectionRelations) {
                    val childId = row.getOrNull(relation.childIdColumn) ?: continue
                    val key = if (childId is EntityID<*>) childId.value else childId
                    val bucket = byProperty.getOrPut(relation.property) { LinkedHashMap() }
                    if (key !in bucket) {
                        val encoded = relation.encodedChildFromRow(row) ?: continue
                        bucket[key] = encoded
                    }
                }
            }
        } catch (_: FoldComplete) {
            // Reached the requested number of distinct parents; stop reading the flow.
        }

        if (parents.isEmpty()) return emptyList()

        return parents.entries.map { (id, entity) ->
            val replacements = collected[id]
                ?.mapValues { (_, byChild) -> byChild.values.toList() }
                .orEmpty()
            if (replacements.isEmpty()) entity else enrich(entity, replacements)
        }
    }

    /**
     * Stream [rows] to entities, keeping only the first row per parent id. With only 1-to-1
     * relations (or no relations at all) the joined query already returns one row per parent,
     * but defensively de-duplicating here keeps the behaviour stable if a future relation
     * accidentally fans out rows without declaring itself a collection.
     */
    private suspend fun distinctRowsToEntities(rows: Flow<ResultRow>, offset: UInt? = null): List<E> {
        val seen = LinkedHashMap<ID, E>()
        // SQL OFFSET is applied at the query level for this path, so callers that already
        // configured the query don't need this fallback. The parameter exists so [foldRows]
        // can delegate uniformly; if [offset] is null we simply collect every row.
        val skip = offset?.toInt() ?: 0
        val skipped = HashSet<ID>()
        rows.collect { row ->
            val parentId = row[table.id].value
            if (parentId in skipped || parentId in seen) return@collect
            if (skipped.size < skip) {
                skipped += parentId
                return@collect
            }
            seen[parentId] = rowToEntity(row)
        }
        return seen.values.toList()
    }

    /**
     * Sentinel used to abort flow collection in [foldRows] once enough distinct parents have
     * been gathered. Subclasses [CancellationException] so it's correctly propagated by the
     * coroutines machinery without being swallowed by intermediate operators.
     */
    private class FoldComplete : CancellationException("fold complete")

    override suspend fun update(e: E) {
        withTransaction {
            table.update({ table.id eq e.id }, limit = 1, assignColumns(e))
            persistRelations(e.id, e)
        }
    }

    override suspend fun updateAndGet(e: E): E =
        withTransaction {
            table.update({ table.id eq e.id }, limit = 1, assignColumns(e))
            persistRelations(e.id, e)
            e
        }

    override suspend fun updateAll(items: Collection<E>) {
        withTransaction {
            for (e in items) {
                table.update({ table.id eq e.id }, limit = 1, assignColumns(e))
                persistRelations(e.id, e)
            }
        }
    }

    /**
     * Run [Relation.persistChildren] for every configured relation, returning a per-property
     * map of the encoded children each relation reported back (suitable to feed into [enrich]).
     *
     * Relations that have nothing to splice back onto the entity (notably [OneToOne], whose
     * FK is already written through `assignParentColumns`) contribute nothing to the map.
     *
     * Must be called inside [withTransaction] so the child writes share the parent's
     * transactional fate.
     */
    protected suspend fun persistRelations(parentId: ID, parent: E): Map<String, Any?> {
        if (relations.isEmpty()) return emptyMap()
        val replacements = mutableMapOf<String, Any?>()
        for (relation in relations) {
            val encoded = relation.persistChildren(parentId as Any, parent) ?: continue
            replacements[relation.property] = encoded
        }
        return replacements
    }

    /**
     * Splice the persisted-child encodings from [replacements] back onto [entity] via
     * [enrich]. Returns [entity] unchanged when there is nothing to replace, so freshly
     * inserted entities without any collection relations still round-trip cheaply.
     */
    protected fun enrichWithPersistedRelations(entity: E, replacements: Map<String, Any?>): E =
        if (replacements.isEmpty()) entity else enrich(entity, replacements)

    override suspend fun delete(id: ID) {
        withTransaction {
            table.deleteWhere { table.id eq id }
        }
    }

    protected suspend fun <T> withTransaction(block: suspend () -> T): T =
        suspendTransaction(database) {
            block()
        }

    protected fun ColumnSet.select(predicate: Predicate): org.jetbrains.exposed.v1.r2dbc.Query =
        when(predicate) {
            Everything -> selectAll()
            else -> selectAll().where { toBooleanOp(predicate)}
        }

    protected fun toBooleanOp(predicate: Predicate): Op<Boolean> =
        when(predicate) {
            Everything -> Op.TRUE
            Nothing -> Op.FALSE
            is And -> AndOp(predicate.clauses.map(::toBooleanOp))
            is Or -> OrOp(predicate.clauses.map(::toBooleanOp))
            is Equals -> table[predicate.field.name].let { column ->
                when(val value = predicate.value) {
                    null -> column.isNull()
                    else -> column.eq(column.coerce(value))
                }
            }
            is IsOneOf<*> -> table[predicate.field.name].let { column ->
                // `coerce` unwraps EntityID-typed columns to their inner column type, so the
                // values it produces are raw (e.g. UInt) rather than EntityID instances. Bind
                // each value through the inner column when present, so `SingleValueInListOp`
                // uses a column type that matches those raw values — otherwise the original
                // column's EntityIDColumnType would try to cast UInt to EntityID at bind time.
                val expr = (column.columnType as? EntityIDColumnType<*>)?.idColumn ?: column
                SingleValueInListOp(expr, predicate.values.map {
                    column.coerce(it).value as Any
                })
            }
            is StringContains -> table[predicate.field.name].let { column ->
                column as Column<String>
                column.like("%${predicate.value}%")
            }
            is CollectionContains<*> -> throw UnsupportedOperationException(
                "CollectionContains predicates are not supported by ExposedR2dbcRepository. " +
                "Collection fields are backed by relations (join tables) and cannot be filtered as simple columns."
            )
        }

    inner class ExposedSelection(val predicate: Predicate): Selection<E> {
        override suspend fun list(): List<E> =
            withTransaction {
                val query = joinedSource.select(predicate)
                foldRows(query)
            }

        override suspend fun page(limit: UInt?, offset: UInt?): Page<E> =
            withTransaction {
                val total = countDistinctParents(predicate)
                val hasCollectionRelations = relations.any { it.isCollection }
                val items = if (hasCollectionRelations) {
                    // The joined query fans out one row per (parent × child-cross-product) so we
                    // can't apply SQL LIMIT directly without truncating a parent's children. Order
                    // by parent id so every parent's row block is contiguous, then let [foldRows]
                    // stream and stop as soon as the requested number of distinct parents is done.
                    val query = joinedSource.select(predicate).orderBy(table.id)
                    foldRows(query, limit = limit, offset = offset)
                } else {
                    // Without collection relations, one parent == one row, so SQL LIMIT/OFFSET is safe and
                    // we can stream directly without any in-memory short-circuit.
                    val query = joinedSource.select(predicate)
                    if (limit != null) query.limit(limit.toInt())
                    if (offset != null) query.offset(offset.toLong())
                    foldRows(query)
                }
                items.asPage(total = total)
            }

        override suspend fun patchAll(values: FieldValues) {
            withTransaction {
                val updateBody: IdTable<ID>.(UpdateBuilder<*>) -> Unit = { stmt ->
                    for ((field, value) in values) {
                        val column = columns.find { it.name == field.name } ?: continue
                        @Suppress("UNCHECKED_CAST")
                        val typedColumn = column as Column<Any?>
                        stmt[typedColumn] = typedColumn.coerce(value).value
                    }
                }
                when (predicate) {
                    Everything -> table.update(body = updateBody)
                    else -> table.update(where = { toBooleanOp(predicate) }, body = updateBody)
                }
            }
        }

        override suspend fun deleteAll() {
            withTransaction {
                when (predicate) {
                    Everything -> table.deleteAll()
                    else -> table.deleteWhere { toBooleanOp(predicate) }
                }
            }
        }

        override suspend fun single(): E {
            return page(limit = 1u).single()
        }

        override suspend fun count(): UInt {
            return countDistinctParents(predicate)
        }
    }

}

operator fun IdTable<*>.get(columnName: String): Column<*> =
    columns.find { it.name == columnName } ?: throw IllegalArgumentException("Unknown field: $columnName")
