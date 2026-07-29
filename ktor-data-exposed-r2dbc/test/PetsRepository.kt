package io.ktor.data.exposed.r2dbc

import io.ktor.data.*
import org.jetbrains.exposed.v1.core.dao.id.UIntIdTable
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase

object PetTypes : UIntIdTable() {
    val name = varchar("name", length = 24)
}

fun PetTypesRepository(database: R2dbcDatabase): Repository<PetType, UInt> =
    ExposedR2bcRepository(database, PetTypes)

object Pets : UIntIdTable() {
    val name = varchar("name", length = 24)
    val typeId = reference("typeId", PetTypes)
    val ownerId = reference("ownerId", Owners)
}

fun PetsRepository(database: R2dbcDatabase): Repository<Pet, UInt> =
    ExposedR2bcRepository(
        database = database,
        table = Pets,
        relations = listOf(
            OneToOne(
                property = "owner",
                childTable = Owners,
                parentFk = Pets.ownerId,
                childSerializer = Owner.serializer(),
                childIdOf = Pet::owner
            ),
            OneToOne(
                property = "type",
                childTable = PetTypes,
                parentFk = Pets.typeId,
                childSerializer = PetType.serializer(),
                childIdOf = Pet::type
            )
        )
    )
