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
    OneOf(this, values)

fun <F> Field<F>.isOneOf(vararg values: F): Predicate =
    OneOf(this, values.toList())

fun Field<String>.contains(value: String): Predicate =
    StringContains(this, value)

fun <F> Field<Collection<F>>.contains(value: F): Predicate =
    CollectionContains(this, value)

fun <F: Comparable<F>> Field<F>.isGreaterThan(value: F): Predicate =
    GreaterThan(this, value)

fun <F: Comparable<F>> Field<F>.isGreaterThanOrEqualTo(value: F): Predicate =
    GreaterThanOrEqualTo(this, value)

fun <F: Comparable<F>> Field<F>.isLessThan(value: F): Predicate =
    LessThan(this, value)

fun <F: Comparable<F>> Field<F>.isLessThanOrEqualTo(value: F): Predicate =
    LessThanOrEqualTo(this, value)

fun <F: Comparable<F>> Field<F>.isBetween(lower: F, upper: F): Predicate =
    isGreaterThanOrEqualTo(lower) and isLessThanOrEqualTo(upper)

operator fun Predicate.not(): Predicate =
    when(this) {
        Everything -> Nothing
        Nothing -> Everything
        is Not -> predicate
        else -> Not(this)
    }

