package io.ktor.data.supabase

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.annotations.SupabaseExperimental
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Count
import io.github.jan.supabase.postgrest.query.PostgrestRequestBuilder
import io.github.jan.supabase.postgrest.query.filter.FilterOperation
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.postgrest.query.filter.PostgrestFilterBuilder
import io.github.jan.supabase.postgrest.result.PostgrestResult
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import io.ktor.data.*
import io.ktor.data.Predicate.*
import io.ktor.data.serialization.toAssignmentsBooleanFunction
import io.ktor.data.serialization.toMap
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import kotlinx.serialization.serializer

/**
 * The [Json] format used to translate entities to and from the PostgREST representation.
 *
 * [Json.encodeDefaults] is on because a PostgREST `PATCH` only writes the columns present in the
 * request body: without it, any property that happens to equal its declared default would be
 * silently left untouched by [SupabaseRepository.update].
 */
val SupabaseRepositoryJson: Json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

/**
 * Create a [SupabaseRepository] for a `@Serializable` entity stored in the Supabase table
 * [tableName], deriving the entity/id encoding and the [Predicate] evaluation used for realtime
 * events from the entity's serializer.
 *
 * @param client the Supabase client, with the `Postgrest` and `Realtime` plugins installed
 * @param tableName the table backing this repository
 * @param schema the Postgres schema holding [tableName]
 * @param idColumn the (serialized) name of the [Identifiable.id] column
 * @param idGeneratedByDatabase when true, the id is stripped from insert bodies so the column
 *   default (`identity`/`serial`/`gen_random_uuid()`) assigns it; set to false to write client-side ids
 * @param json the format used to encode and decode entities
 */
inline fun <reified E: Identifiable<ID>, reified ID> SupabaseRepository(
    client: SupabaseClient,
    tableName: String,
    schema: String = client.postgrest.config.defaultSchema,
    idColumn: String = "id",
    idGeneratedByDatabase: Boolean = true,
    json: Json = SupabaseRepositoryJson,
): SupabaseRepository<E, ID> {
    val serializer = try {
        serializer<E>()
    } catch (e: SerializationException) {
        throw IllegalArgumentException("SupabaseRepository(client, tableName) expects a @Serializable entity", e)
    }
    val idSerializer = serializer<ID>()
    return SupabaseRepository(
        client = client,
        tableName = tableName,
        schema = schema,
        idColumn = idColumn,
        idGeneratedByDatabase = idGeneratedByDatabase,
        encodeEntity = { e: E -> json.encodeToJsonElement(serializer, e).jsonObject },
        decodeEntities = { data: String -> json.decodeFromString(ListSerializer(serializer), data) },
        decodeEntity = { record: JsonObject -> json.decodeFromJsonElement(serializer, record) },
        decodeId = { value: JsonElement -> json.decodeFromJsonElement(idSerializer, value) },
        toBooleanFunction = {
            val fieldValuesPredicate = toAssignmentsBooleanFunction()
            ({ e: E -> fieldValuesPredicate(e.toMap(serializer)) })
        },
    )
}

/**
 * Uses the Supabase PostgREST API with the Realtime API to provide a reactive repository.
 *
 * Reads and writes go through PostgREST, so they are subject to the row level security policies of
 * the authenticated session held by [client]. [ObservableSelection.changeFlow] subscribes to the
 * table's `postgres_changes` replication stream; realtime must be enabled for the table (and, for
 * [ChangeEvent.Deleted] to carry the deleted entity rather than just its id, the table needs
 * `REPLICA IDENTITY FULL`).
 *
 * Prefer the `SupabaseRepository(client, tableName)` factory, which derives the encoding functions
 * below from the entity's [kotlinx.serialization.KSerializer].
 *
 * @property client the Supabase client, with the `Postgrest` and `Realtime` plugins installed
 * @property tableName the table backing this repository
 * @property schema the Postgres schema holding [tableName]
 * @property idColumn the (serialized) name of the [Identifiable.id] column
 * @param idGeneratedByDatabase when true, the id is stripped from insert bodies so the column
 *   default assigns it
 * @param encodeEntity encodes an entity as the row representation sent to PostgREST
 * @param decodeEntities decodes a PostgREST response body into entities
 * @param decodeEntity decodes a single realtime record into an entity
 * @param decodeId decodes an id from a realtime record's primary key column
 * @param toBooleanFunction compiles a [Predicate] into a test applied to realtime events, which
 *   the server side filter cannot express in full
 */
class SupabaseRepository<E: Identifiable<ID>, ID>(
    val client: SupabaseClient,
    val tableName: String,
    val schema: String = "public",
    val idColumn: String = "id",
    private val idGeneratedByDatabase: Boolean = true,
    private val encodeEntity: (E) -> JsonObject,
    private val decodeEntities: (String) -> List<E>,
    private val decodeEntity: (JsonObject) -> E,
    private val decodeId: (JsonElement) -> ID,
    private val toBooleanFunction: Predicate.() -> (E) -> Boolean,
): ObservableRepository<E, ID> {
    private val table = client.postgrest[schema, tableName]
    private val channelCounter = atomic(0)

    override fun find(predicate: Predicate): ObservableSelection<E, ID> =
        SupabaseSelection(predicate)

    override suspend fun get(id: ID): E? =
        table.select {
            filter { eqId(id) }
            limit(1)
        }.entities().firstOrNull()

    override suspend fun create(e: E) {
        table.insert(JsonArray(listOf(insertBody(e))))
    }

    override suspend fun createAndGet(e: E): E =
        table.insert(JsonArray(listOf(insertBody(e)))) {
            select()
        }.entities().first()

    override suspend fun createAll(items: Collection<E>) {
        if (items.isEmpty()) return
        table.insert(JsonArray(items.map(::insertBody)))
    }

    override suspend fun update(e: E) {
        table.update(body = encodeEntity(e)) {
            filter { eqId(e.id) }
        }
    }

    override suspend fun updateAndGet(e: E): E =
        table.update(body = encodeEntity(e)) {
            filter { eqId(e.id) }
            select()
        }.entities().first()

    /**
     * Updates each item in its own request: PostgREST has no batch update, and an upsert would
     * insert rows for ids that are no longer present rather than leaving them alone.
     */
    override suspend fun updateAll(items: Collection<E>) {
        for (e in items) update(e)
    }

    override suspend fun delete(id: ID) {
        table.delete {
            filter { eqId(id) }
        }
    }

    /** The row representation to insert, without the id when the database generates it. */
    private fun insertBody(e: E): JsonObject =
        encodeEntity(e).let { row ->
            if (idGeneratedByDatabase) JsonObject(row - idColumn) else row
        }

    private fun PostgrestResult.entities(): List<E> =
        decodeEntities(data)

    private fun PostgrestFilterBuilder.eqId(id: ID) {
        filter(idColumn, FilterOperator.EQ, id)
    }

    /** Applies [predicate] to a request, leaving [Everything] unfiltered. */
    private fun PostgrestRequestBuilder.where(predicate: Predicate) {
        if (predicate == Everything) return
        filter { applyPredicate(predicate) }
    }

    private fun PostgrestFilterBuilder.applyPredicate(predicate: Predicate) {
        when (predicate) {
            Everything -> {}
            // A primary key is never null, so this matches no rows. Reachable only from inside a
            // logical grouping; the selection short-circuits a top level `Nothing` without a request.
            Predicate.Nothing -> filter(idColumn, FilterOperator.IS, null)
            is Equals -> when (val value = predicate.value) {
                null -> filter(predicate.field.name, FilterOperator.IS, null)
                else -> filter(predicate.field.name, FilterOperator.EQ, value)
            }
            is IsOneOf<*> -> isIn(predicate.field.name, predicate.values.filterNotNull())
            is StringContains -> like(predicate.field.name, "%${predicate.value}%")
            is CollectionContains<*> -> contains(predicate.field.name, predicate.value.filterNotNull())
            is And -> and { predicate.clauses.forEach { applyPredicate(it) } }
            is Or -> or { predicate.clauses.forEach { applyPredicate(it) } }
        }
    }

    /**
     * The part of [predicate] that `postgres_changes` can evaluate server side, or null when it
     * can't express any of it. Realtime accepts a single simple comparison per subscription, so
     * anything else is filtered on the client by [toBooleanFunction] — which happens either way,
     * making this purely an optimisation that keeps unrelated changes off the socket.
     */
    private fun realtimeFilter(predicate: Predicate): FilterOperation? =
        when (predicate) {
            is Equals -> predicate.value?.let { FilterOperation(predicate.field.name, FilterOperator.EQ, it) }
            is IsOneOf<*> -> FilterOperation(predicate.field.name, FilterOperator.IN, predicate.values.filterNotNull())
            else -> null
        }

    private fun PostgresAction.toChangeEvent(): ChangeEvent<E>? =
        when (this) {
            is PostgresAction.Insert -> ChangeEvent.Created(decodeEntity(record))
            is PostgresAction.Update -> ChangeEvent.Updated(decodeEntity(record))
            is PostgresAction.Delete -> {
                // Without `REPLICA IDENTITY FULL` the old record only carries the primary key, so
                // the entity is best effort while the id — all a delete really needs — is not.
                val id = oldRecord[idColumn]?.let(decodeId)
                id?.let { ChangeEvent.Deleted(it, runCatching { decodeEntity(oldRecord) }.getOrNull()) }
            }
            // Replayed initial state, not a change: `list()`/`page()` already provide it.
            is PostgresAction.Select -> null
        }

    /** Realtime topics are shared per name, so every subscription gets one of its own. */
    private fun nextChannelName(): String =
        "$schema:$tableName:${channelCounter.incrementAndGet()}"

    inner class SupabaseSelection(val predicate: Predicate): ObservableSelection<E, ID> {
        private val matchesNothing get() = predicate == Predicate.Nothing

        override suspend fun list(): List<E> {
            if (matchesNothing) return emptyList()
            return table.select { where(predicate) }.entities()
        }

        override suspend fun page(limit: UInt?, offset: UInt?): Page<E> {
            if (matchesNothing) return emptyList<E>().asPage(total = 0u)
            // PostgREST has no zero-length range, and the total is the only thing left to report.
            if (limit == 0u) return emptyList<E>().asPage(total = count())
            val result = table.select {
                count(Count.EXACT)
                where(predicate)
                paginate(limit, offset)
            }
            val items = result.entities()
            return items.asPage(total = result.countOrNull()?.toUInt() ?: items.size.toUInt())
        }

        override suspend fun single(): E {
            check(!matchesNothing) { "No entity matches Predicate.Nothing" }
            // Two rows is enough to tell "exactly one" from "more than one".
            return table.select {
                where(predicate)
                limit(2)
            }.entities().single()
        }

        override suspend fun count(): UInt {
            if (matchesNothing) return 0u
            val result = table.select(Columns.list(idColumn)) {
                count(Count.EXACT)
                where(predicate)
                limit(1)
            }
            return result.countOrNull()?.toUInt() ?: 0u
        }

        override suspend fun patchAll(values: FieldValues) {
            if (matchesNothing || values.isEmpty()) return
            table.update(body = values.toRow()) { where(predicate) }
        }

        override suspend fun deleteAll() {
            if (matchesNothing) return
            table.delete { where(predicate) }
        }

        override suspend fun changeFlow(): Flow<ChangeEvent<E>> {
            if (matchesNothing) return emptyFlow()
            val matches = predicate.toBooleanFunction()
            val serverFilter = realtimeFilter(predicate)
            return flow {
                val realtime = client.realtime
                val channel = realtime.channel(nextChannelName())
                val actions = channel.postgresChangeFlow<PostgresAction>(schema) {
                    table = tableName
                    serverFilter?.let { filter(it) }
                }
                channel.subscribe()
                try {
                    emitAll(
                        actions
                            .mapNotNull { it.toChangeEvent() }
                            // Deletes that couldn't carry an entity are passed through, matching
                            // ObservableListRepository: the id is still worth reporting.
                            .filter { event -> event.entity?.let(matches) ?: true }
                    )
                } finally {
                    withContext(NonCancellable) { realtime.removeChannel(channel) }
                }
            }
        }
    }
}

/**
 * Translates a limit/offset pair into the equivalent PostgREST range parameters.
 *
 * An offset without a limit is set directly, because [PostgrestRequestBuilder.range] can only
 * express it as a bounded range — which would send an arbitrary, and arbitrarily wrong, limit.
 */
@OptIn(SupabaseExperimental::class)
private fun PostgrestRequestBuilder.paginate(limit: UInt?, offset: UInt?) {
    val from = (offset ?: 0u).toLong()
    when {
        limit != null -> range(from = from, to = from + limit.toLong() - 1)
        offset != null -> params["offset"] = listOf(from.toString())
        else -> {}
    }
}

/**
 * Encodes patched [FieldValues] as a PostgREST row.
 *
 * Unlike whole-entity writes there is no serializer to go by here — a [Field] only carries a name
 * and a [kotlin.reflect.KType] — so values are mapped structurally, and anything not recognised is
 * sent as its `toString()`.
 */
@OptIn(ExperimentalSerializationApi::class)
private fun FieldValues.toRow(): JsonObject =
    JsonObject(entries.associate { (field, value) -> field.name to value.toJsonElement() })

@OptIn(ExperimentalSerializationApi::class)
private fun Any?.toJsonElement(): JsonElement =
    when (this) {
        null -> JsonNull
        is JsonElement -> this
        is String -> JsonPrimitive(this)
        is Boolean -> JsonPrimitive(this)
        is Number -> JsonPrimitive(this)
        is UByte -> JsonPrimitive(toLong())
        is UShort -> JsonPrimitive(toLong())
        is UInt -> JsonPrimitive(toLong())
        // Values above Long.MAX_VALUE can't round-trip through JsonPrimitive(Number).
        is ULong -> JsonUnquotedLiteral(toString())
        is Enum<*> -> JsonPrimitive(name)
        is Iterable<*> -> JsonArray(map { it.toJsonElement() })
        is Map<*, *> -> JsonObject(entries.associate { (key, value) -> key.toString() to value.toJsonElement() })
        else -> JsonPrimitive(toString())
    }
