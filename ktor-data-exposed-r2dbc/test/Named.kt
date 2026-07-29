package io.ktor.data.exposed.r2dbc

import io.ktor.data.Identifiable

interface Named<ID>: Identifiable<ID> {
    val name: String
}