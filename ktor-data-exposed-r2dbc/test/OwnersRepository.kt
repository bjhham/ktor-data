package io.ktor.data.exposed.r2dbc

import io.ktor.data.*
import org.jetbrains.exposed.v1.core.dao.id.UIntIdTable
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase

object Owners : UIntIdTable() {
    val firstName = varchar("firstName", length = 24)
    val lastName = varchar("lastName", length = 24)
    val address = varchar("address", length = 64)
    val city = varchar("city", length = 24)
    val telephone = varchar("telephone", length = 12)
}

fun OwnersRepository(database: R2dbcDatabase): Repository<Owner, UInt> =
    ExposedR2bcRepository(database, Owners, relations = listOf(
        OneToMany(
            property = "pets",
            childTable = Pets,
            parentFk = Pets.ownerId,
            childSerializer = Pet.serializer(),
        )
    ))
