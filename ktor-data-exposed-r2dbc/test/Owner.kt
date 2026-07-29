package io.ktor.data.exposed.r2dbc

import kotlinx.serialization.Serializable

@Serializable
data class Owner(
    override val id: UInt,
    override val firstName: String,
    override val lastName: String,
    val address: String,
    val city: String,
    val telephone: String,
    val pets: List<Pet> = emptyList(),
): Person
