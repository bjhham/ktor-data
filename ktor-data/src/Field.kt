/*
 * Copyright 2014-2026 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.data

import kotlin.reflect.KType
import kotlin.reflect.typeOf

inline fun <reified F> Field(name: String): Field<F> =
    Field(name, typeOf<F>())

class Field<F>(
    val name: String,
    val type: KType?,
) {
    override fun hashCode(): Int =
        name.hashCode()

    override fun equals(other: Any?): Boolean =
        other is Field<*> && name == other.name
}