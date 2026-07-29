package io.ktor.data.exposed.r2dbc

import kotlinx.serialization.Serializable

@Serializable
data class Vet(
    override val id: UInt,
    override val firstName: String,
    override val lastName: String,
    val skills: List<Skill> = emptyList(),
): Person
