package io.ktor.data.exposed.r2dbc

import io.ktor.data.*
import org.jetbrains.exposed.v1.core.dao.id.UIntIdTable
import org.jetbrains.exposed.v1.datetime.datetime
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase

object Visits : UIntIdTable() {
    val date = datetime("date")
    val description = varchar("description", length = 256)
    val petId = reference("petId", Pets.id)
    val ownerId = reference("ownerId", Owners.id)
    val vetId = reference("vetId", Vets.id)
}

fun VisitsRepository(database: R2dbcDatabase): Repository<Visit, UInt> =
    ExposedR2bcRepository(
        database = database,
        table = Visits,
        relations = listOf(
            OneToOne(
                property = "pet",
                childTable = Pets,
                parentFk = Visits.petId,
                childSerializer = Pet.serializer(),
                childIdOf = Visit::pet,
            ),
            OneToOne(
                property = "owner",
                childTable = Owners,
                parentFk = Visits.ownerId,
                childSerializer = Owner.serializer(),
                childIdOf = Visit::owner,
            ),
            OneToOne(
                property = "vet",
                childTable = Vets,
                parentFk = Visits.vetId,
                childSerializer = Vet.serializer(),
                childIdOf = Visit::vet,
            ),
        ),
    )
