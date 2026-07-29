package io.ktor.data.exposed.r2dbc

import io.ktor.data.Repository
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.dao.id.UIntIdTable
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase

object Vets : UIntIdTable() {
    val firstName = varchar("firstName", length = 24)
    val lastName = varchar("lastName", length = 24)
}

object Skills : UIntIdTable() {
    val name = varchar("name", length = 32)
}

object VetSkills : Table() {
    val vetId = reference("vetId", Vets)
    val skillId = reference("skillId", Skills)
    override val primaryKey = PrimaryKey(vetId, skillId)
}

fun VetsRepository(database: R2dbcDatabase): Repository<Vet, UInt> =
    ExposedR2bcRepository(
        database = database,
        table = Vets,
        relations = listOf(
            ManyToMany(
                property = "skills",
                joinTable = VetSkills,
                joinParentFk = VetSkills.vetId,
                joinChildFk = VetSkills.skillId,
                childTable = Skills,
                childSerializer = Skill.serializer(),
                childrenOf = Vet::skills,
            ),
        ),
    )

fun SkillsRepository(database: R2dbcDatabase): Repository<Skill, UInt> =
    ExposedR2bcRepository(database, Skills)
