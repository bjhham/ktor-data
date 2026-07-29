package io.ktor.data.exposed.r2dbc

interface Person: Named<UInt> {
    val firstName: String
    val lastName: String

    override val name: String
        get() = "$firstName $lastName"
}
