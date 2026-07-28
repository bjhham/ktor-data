package io.ktor.data

import io.ktor.data.Predicate.*

infix fun Predicate.and(other: Predicate): Predicate =
    when(this) {
        Everything -> other
        Nothing -> Nothing
        else -> when(other) {
            Everything -> this
            Nothing -> Nothing
            else -> And(listOf(this, other).flatMap {
                if (it is And) it.clauses
                else listOf(it)
            })
        }
    }

infix fun Predicate.or(other: Predicate): Predicate =
    when(this) {
        Everything -> Everything
        Nothing -> other
        else -> when(other) {
            Everything -> Everything
            Nothing -> this
            else -> Or(listOf(this, other).flatMap {
                if (it is Or) it.clauses
                else listOf(it)
            })
        }
    }

fun <F> Field<F>.isEqualTo(value: F): Predicate =
    Equals(this, value)

fun <F> Field<F>.isOneOf(values: Iterable<F>): Predicate =
    IsOneOf(this, values)

fun <F> Field<F>.isOneOf(vararg values: F): Predicate =
    IsOneOf(this, values.toList())

fun Field<String>.contains(value: String): Predicate =
    StringContains(this, value)

fun <F> Field<Collection<F>>.contains(value: Collection<F>): Predicate =
    CollectionContains(this, value)

