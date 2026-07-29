package io.ktor.data.exposed.r2dbc

import io.ktor.data.*
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
data class Visit(
    override val id: UInt,
    val date: Instant,
    val description: String,
    val pet: Pet,
    val owner: Owner,
    val vet: Vet,
): Identifiable<UInt>
