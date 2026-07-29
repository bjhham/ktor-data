package io.ktor.data.exposed.r2dbc

import kotlinx.serialization.Serializable

@Serializable
data class Pet(
    override val id: UInt,
    override val name: String,
    val type: PetType,
    val owner: Owner,
): Named<UInt>

@Serializable
data class PetType(
    override val id: UInt,
    override val name: String
): Named<UInt>
