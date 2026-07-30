package io.ktor.data.todo.server

import TodoItem
import io.ktor.data.*
import io.ktor.data.exposed.r2dbc.*
import io.ktor.server.application.*
import io.ktor.server.plugins.di.*
import io.ktor.server.plugins.di.annotations.*
import org.jetbrains.exposed.v1.core.dao.id.UIntIdTable
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.SchemaUtils
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

suspend fun Application.configureDatabase(
    @Property("database.url") url: String,
    @Property("database.user")  user: String,
    @Property("database.driver")  driver: String,
    @Property("database.password")  password: String
) {
    val db = R2dbcDatabase.connect(
        url = url,
        user = user,
        password = password
    )
    suspendTransaction(db) {
        SchemaUtils.create(TodoItems)
    }
    dependencies {
        provide<Repository<TodoItem, UInt>> {
            ExposedR2bcRepository(db, TodoItems)
        }
    }
}

object TodoItems : UIntIdTable() {
    val text = varchar("text", length = 24)
}