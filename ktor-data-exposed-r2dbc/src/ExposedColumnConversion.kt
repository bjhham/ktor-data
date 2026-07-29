package io.ktor.data.exposed.r2dbc

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.v1.core.AutoIncColumnType
import org.jetbrains.exposed.v1.core.BooleanColumnType
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.EntityIDColumnType
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.QueryParameter
import org.jetbrains.exposed.v1.core.StringColumnType
import org.jetbrains.exposed.v1.core.UIntegerColumnType
import org.jetbrains.exposed.v1.core.ULongColumnType
import org.jetbrains.exposed.v1.datetime.KotlinLocalDateTimeColumnType
import kotlin.time.Instant

/**
 * Generic utility function for coercing unknown types to the expected column types.
 */
@Suppress("UNCHECKED_CAST")
fun Column<*>.coerce(value: Any?): QueryParameter<*> =
    coerce(value, columnType as IColumnType<*>)

private fun coerce(value: Any?, columnType: IColumnType<*>): QueryParameter<*> =
    when(columnType) {
        is StringColumnType -> QueryParameter(value.toString(), columnType as IColumnType<String>)
        is UIntegerColumnType -> QueryParameter(value.toString().toUInt(), columnType as IColumnType<UInt>)
        is ULongColumnType -> QueryParameter(value.toString().toULong(), columnType as IColumnType<ULong>)
        is BooleanColumnType -> QueryParameter(value.toString().toBoolean(), columnType as IColumnType<Boolean>)
        is IntegerColumnType -> QueryParameter(value.toString().toInt(), columnType as IColumnType<Int>)
        is EntityIDColumnType<*> -> coerce(value, columnType.idColumn.columnType as IColumnType<*>)
        is AutoIncColumnType<*> -> coerce(value, columnType.delegate)
        is KotlinLocalDateTimeColumnType -> QueryParameter(
            Instant.parse(value.toString()).toLocalDateTime(TimeZone.currentSystemDefault()),
            columnType as IColumnType<LocalDateTime>
        )
        else -> throw UnknownColumnTypeException(columnType)
    }

class UnknownColumnTypeException(columnType: IColumnType<*>):
    IllegalArgumentException("Unsupported column type: ${columnType::class.simpleName}")
