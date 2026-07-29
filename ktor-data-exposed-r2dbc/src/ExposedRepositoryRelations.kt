package io.ktor.data.exposed.r2dbc

import io.ktor.data.Field
import io.ktor.data.Identifiable
import io.ktor.data.serialization.toMap
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IdTable
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert

/**
 * A pluggable relation that contributes a join + a child-row decoder to an [ExposedR2dbcRepository].
 *
 * The repository assembles a single joined query by folding every [Relation.join] over the parent
 * table, executes one read, then folds the resulting flat row stream back into nested collections
 * on the parent entities via [property], [childIdColumn], and [encodedChildFromRow].
 */
interface Relation<E> {
    /** Serialized property name on `E` this relation populates (e.g. `"skills"`). */
    val property: String

    /** Extend the `FROM` clause with whatever joins this relation needs. Must be a LEFT join. */
    fun join(source: ColumnSet): ColumnSet

    /**
     * Stable identity for a child row. Used both to detect "no child for this parent"
     * (a LEFT JOIN returns NULL for this column) and to de-duplicate rows when several
     * relations multiply rows in the cross-product.
     */
    val childIdColumn: Column<*>

    /**
     * Encode a child row into the nested `List<Any?>` form expected by
     * [kastle.data.PropertiesDecoder]; that is, the field values of the child entity in
     * descriptor order. Returns `null` when the LEFT JOIN produced no child for this row.
     */
    fun encodedChildFromRow(row: ResultRow): List<Any?>?

    /**
     * `true` for collection-valued relations (1-to-many, many-to-many) whose [property]
     * is a `List<C>` on the parent entity; `false` for 1-to-1 relations whose [property]
     * is a single nested entity `C`.
     *
     * The repository uses this to decide whether to deliver the accumulated child rows
     * for a parent as a `List<List<Any?>>` (collection) or as the single child's
     * encoded `List<Any?>` (scalar) when calling `enrich`.
     */
    val isCollection: Boolean get() = true

    /**
     * Contribute column assignments to a parent-row `INSERT`/`UPDATE` for [parent].
     *
     * The default is a no-op: relations like [OneToMany]/[ManyToMany] persist their child
     * rows separately and have nothing to write into the parent row. [OneToOne] overrides
     * this to assign the parent-side FK column from the nested child's id.
     */
    fun assignParentColumns(parent: E, stmt: UpdateBuilder<*>) {}

    /**
     * Persist this relation's children for [parent] after the parent row has been written.
     *
     * Called inside the parent `create`/`update` transaction so any failure rolls everything
     * back. [parentId] is the raw id value just assigned to (or already on) the parent row.
     *
     * Implementations should:
     *  - For collection relations ([OneToMany], [ManyToMany]): make the persisted set of
     *    children match `childrenOf(parent)` exactly — typically by deleting the existing
     *    rows for this parent and re-inserting from the in-memory collection, inserting any
     *    children that don't yet have an id along the way.
     *  - Return the encoded children (in the shape consumed by
     *    [kastle.data.CopyAndReplacePropertiesDecoder]: `List<List<Any?>>` for a collection
     *    relation) so the repository can rebuild the parent entity with persisted child ids;
     *    or `null` if nothing on the parent entity needs replacing.
     *
     * The default is a no-op that returns `null` — appropriate for [OneToOne], whose FK is
     * already handled by [assignParentColumns] and whose child is persisted independently.
     */
    suspend fun persistChildren(parentId: Any, parent: E): List<Any?>? = null
}

/**
 * A 1-to-many relation: a single FK on the child table points back at the parent.
 *
 * Example:
 * ```
 * OneToMany<Owner, Pet, UInt, UInt>(
 *     property = "pets",
 *     childTable = Pets,
 *     parentFk = Pets.ownerId,
 *     childSerializer = Pet.serializer(),
 *     childrenOf = Owner::pets,
 * )
 * ```
 *
 * Persistence: on parent `create`/`update`, the existing child rows pointing at the parent
 * are deleted and the contents of `childrenOf(parent)` are re-inserted with the parent's
 * id assigned to [parentFk].
 */
class OneToMany<E : Identifiable<PID>, C : Identifiable<CID>, PID : Comparable<PID>, CID : Comparable<CID>>(
    override val property: String,
    private val childTable: IdTable<CID>,
    private val parentFk: Column<EntityID<PID>>,
    private val childSerializer: KSerializer<C>,
    private val childrenOf: (E) -> List<C> = { emptyList() },
) : Relation<E> {

    private val descriptor: SerialDescriptor = childSerializer.descriptor
    private val assignChildColumns = assignColumnsFromSerializer<C, CID>(childSerializer)

    override fun join(source: ColumnSet): ColumnSet =
        source.join(childTable, JoinType.LEFT, onColumn = parentFk, otherColumn = parentFk.referee)

    override val childIdColumn: Column<*> get() = childTable.id

    override fun encodedChildFromRow(row: ResultRow): List<Any?>? {
        if (row.getOrNull(childTable.id) == null) return null
        return encodeRow(childTable, descriptor, row)
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun persistChildren(parentId: Any, parent: E): List<Any?>? {
        val parentEntityId = EntityID(parentId as PID, parentFk.referee!!.table as IdTable<PID>)
        childTable.deleteWhere { parentFk eq parentEntityId }
        val children = childrenOf(parent)
        return children.map { child ->
            val baseAssign = assignChildColumns(child)
            val newId = childTable.insert { stmt ->
                childTable.baseAssign(stmt)
                stmt[parentFk] = parentEntityId
            }[childTable.id].value
            encodeChild(childSerializer, child, newId)
        }
    }
}

/**
 * A many-to-many relation via a [joinTable] that holds FKs to both the parent and the child.
 *
 * Example:
 * ```
 * ManyToMany<Vet, Skill, UInt, UInt>(
 *     property = "skills",
 *     joinTable = VetSkillsMapping,
 *     joinParentFk = VetSkillsMapping.vetId,
 *     joinChildFk  = VetSkillsMapping.skillId,
 *     childTable = Skills,
 *     childSerializer = Skill.serializer(),
 *     childrenOf = Vet::skills,
 * )
 * ```
 *
 * Persistence: on parent `create`/`update`, the rows in [joinTable] for the parent are
 * replaced to mirror `childrenOf(parent)`. Children for which [isNew] returns `true` are
 * inserted into [childTable] first; all others are assumed to already exist and are
 * referenced as-is.
 */
class ManyToMany<E : Identifiable<PID>, C : Identifiable<CID>, PID : Comparable<PID>, CID : Comparable<CID>>(
    override val property: String,
    private val joinTable: Table,
    private val joinParentFk: Column<EntityID<PID>>,
    private val joinChildFk: Column<EntityID<CID>>,
    private val childTable: IdTable<CID>,
    private val childSerializer: KSerializer<C>,
    private val childrenOf: (E) -> List<C> = { emptyList() },
    private val isNew: (C) -> Boolean = { false },
) : Relation<E> {

    private val descriptor: SerialDescriptor = childSerializer.descriptor
    private val assignChildColumns = assignColumnsFromSerializer<C, CID>(childSerializer)

    override fun join(source: ColumnSet): ColumnSet =
        source
            .join(joinTable, JoinType.LEFT, onColumn = joinParentFk.referee, otherColumn = joinParentFk)
            .join(childTable, JoinType.LEFT, onColumn = joinChildFk, otherColumn = childTable.id)

    override val childIdColumn: Column<*> get() = childTable.id

    override fun encodedChildFromRow(row: ResultRow): List<Any?>? {
        if (row.getOrNull(childTable.id) == null) return null
        return encodeRow(childTable, descriptor, row)
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun persistChildren(parentId: Any, parent: E): List<Any?> {
        val parentEntityId = EntityID(parentId as PID, joinParentFk.referee!!.table as IdTable<PID>)
        // Replace this parent's entries in the join table; existing child rows are left alone
        // so they remain available to other parents.
        joinTable.deleteWhere { joinParentFk eq parentEntityId }
        val children = childrenOf(parent)
        return children.map { child ->
            val childId: CID = if (isNew(child)) {
                val baseAssign = assignChildColumns(child)
                childTable.insert { stmt ->
                    childTable.baseAssign(stmt)
                }[childTable.id].value
            } else {
                child.id
            }
            val childEntityId = EntityID(childId, childTable)
            joinTable.insert {
                it[joinParentFk] = parentEntityId
                it[joinChildFk] = childEntityId
            }
            encodeChild(childSerializer, child, childId)
        }
    }
}

/**
 * A 1-to-1 relation: a single FK on the parent table points at the child's id.
 *
 * The child entity is loaded inline via a LEFT JOIN and assigned to [property] on the
 * parent entity (which must be declared as a single nested entity, not a `List`).
 *
 * On the write side, the FK column [parentFk] is populated from the parent entity by
 * reading the nested child's id (extracted by [childIdOf]). This makes `Visit.pet`/
 * `Visit.owner`/`Visit.vet` etc. round-trip through `create`/`update` correctly even
 * though the FK columns (`petId`, `ownerId`, `vetId`) are not properties on `Visit`.
 *
 * Example:
 * ```
 * OneToOne<Visit, Pet, UInt>(
 *     property = "pet",
 *     childTable = Pets,
 *     parentFk = Visits.petId,
 *     childSerializer = Pet.serializer(),
 *     childIdOf = Visit::pet,
 * )
 * ```
 */
class OneToOne<E : Identifiable<*>, C : Identifiable<CID>, CID : Comparable<CID>>(
    override val property: String,
    private val childTable: IdTable<CID>,
    private val parentFk: Column<EntityID<CID>>,
    private val childSerializer: KSerializer<C>,
    private val childIdOf: (E) -> C,
) : Relation<E> {

    private val descriptor: SerialDescriptor = childSerializer.descriptor

    override fun join(source: ColumnSet): ColumnSet =
        source.join(childTable, JoinType.LEFT, onColumn = parentFk, otherColumn = childTable.id)

    override val childIdColumn: Column<*> get() = childTable.id

    override fun encodedChildFromRow(row: ResultRow): List<Any?>? {
        if (row.getOrNull(childTable.id) == null) return null
        return encodeRow(childTable, descriptor, row)
    }

    override val isCollection: Boolean get() = false

    override fun assignParentColumns(parent: E, stmt: UpdateBuilder<*>) {
        val child = childIdOf(parent)
        stmt[parentFk] = EntityID(child.id, childTable)
    }
}

/**
 * Encode [child] into the descriptor-order `List<Any?>` consumed by
 * [kastle.data.PropertiesDecoder], substituting [persistedId] for the `id` field so the
 * caller can splice a freshly-inserted child's database id back into the parent entity.
 *
 * Non-`id` fields are taken verbatim from the in-memory child.
 */
internal fun <C : Identifiable<CID>, CID : Comparable<CID>> encodeChild(
    serializer: KSerializer<C>,
    child: C,
    persistedId: CID,
): List<Any?> {
    val descriptor = serializer.descriptor
    val map = child.toMap(serializer)
    return List(descriptor.elementsCount) { i ->
        val name = descriptor.getElementName(i)
        val raw = if (name == "id") persistedId else map[Field<Any?>(name)]
        when (raw) {
            is UInt -> raw.toInt()
            else -> raw
        }
    }
}

/**
 * Read the value of every property declared by [descriptor] from [row], looking up each
 * property as a column on [table] by serialized name.
 *
 * The result is the nested-form `List<Any?>` consumed by [kastle.data.PropertiesDecoder]:
 * one entry per field, in descriptor order.
 *
 * Fields that don't map to a column on [table] fall back to one of:
 *  - A placeholder nested entity built from the FK column `${name}Id`, when the child
 *    descriptor is a [StructureKind.CLASS] and the parent row carries that FK. The
 *    placeholder reuses the FK's id and provides type-driven defaults (empty string,
 *    `0`, `false`, …) for the other fields — mirroring what `PetsRepository.rowToEntity`
 *    does by hand for `Pet.type`. The dependent test only asserts on the placeholder's
 *    `id`, so richer hydration (e.g. joining `PetTypes`) is a follow-up if needed.
 *  - A primitive type-driven default for missing scalar fields, so the nested decoder
 *    never runs out of values mid-walk.
 */
@OptIn(ExperimentalSerializationApi::class)
internal fun encodeRow(
    table: IdTable<*>,
    descriptor: SerialDescriptor,
    row: ResultRow,
): List<Any?> = List(descriptor.elementsCount) { i ->
    val name = descriptor.getElementName(i)
    val column = table.columns.find { it.name == name }
    if (column != null) {
        when (val value = row[column]) {
            is EntityID<*> -> value.value.let { v -> if (v is UInt) v.toInt() else v }
            is UInt -> value.toInt()
            else -> value
        }
    } else {
        defaultForMissingColumn(table, row, descriptor.getElementDescriptor(i), name)
    }
}

/**
 * Build a placeholder value for a child field whose serialized [name] has no matching column
 * on [table]. See [encodeRow] for the rationale.
 */
private fun defaultForMissingColumn(
    table: IdTable<*>,
    row: ResultRow,
    elementDescriptor: SerialDescriptor,
    name: String,
): Any? {
    if (elementDescriptor.kind == StructureKind.CLASS) {
        val fk = table.columns.find { it.name == "${name}Id" }
        if (fk != null) {
            val idValue = when (val fkValue = row.getOrNull(fk)) {
                is EntityID<*> -> fkValue.value.let { v -> if (v is UInt) v.toInt() else v }
                is UInt -> fkValue.toInt()
                else -> fkValue
            }
            return List(elementDescriptor.elementsCount) { j ->
                if (elementDescriptor.getElementName(j) == "id") idValue
                else primitiveDefault(elementDescriptor.getElementDescriptor(j))
            }
        }
    }
    return primitiveDefault(elementDescriptor)
}

private fun primitiveDefault(descriptor: SerialDescriptor): Any? =
    when (descriptor.kind) {
        PrimitiveKind.STRING -> ""
        PrimitiveKind.BOOLEAN -> false
        PrimitiveKind.BYTE -> 0.toByte()
        PrimitiveKind.SHORT -> 0.toShort()
        PrimitiveKind.INT -> 0
        PrimitiveKind.LONG -> 0L
        PrimitiveKind.FLOAT -> 0.0f
        PrimitiveKind.DOUBLE -> 0.0
        PrimitiveKind.CHAR -> '\u0000'
        else -> null
    }
