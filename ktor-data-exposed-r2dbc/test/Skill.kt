package io.ktor.data.exposed.r2dbc

import kotlinx.serialization.Serializable

@Serializable
data class Skill(
    override val id: UInt,
    override val name: String
): Named<UInt>
