package io.ktor.data

/**
 * A very basic query language for demonstration purposes.
 */
sealed interface Predicate {
    /**
     * Sealed interface for any predicate that matches on a property.
     */
    sealed interface FieldClause: Predicate {
        fun test(field: Field<*>, value: Any?): Boolean
    }
    sealed interface FieldComparison: FieldClause {
        val field: Field<*>
        val expected: Any?
    }

    /**
     * Always true predicate.
     */
    data object Everything: Predicate

    /**
     * Always false predicate.
     */
    data object Nothing: Predicate

    /**
     * A clause that matches a single key-value pair.
     */
    class Equals(override val field: Field<*>, override val expected: Any?): FieldComparison {
        override fun test(field: Field<*>, value: Any?): Boolean =
            field == this.field && value == expected
    }

    /**
     * A clause that matches if a key-value pair is one of the given values.
     */
    class OneOf<F>(override val field: Field<F>, override val expected: Iterable<F>): FieldComparison {
        override fun test(field: Field<*>, value: Any?): Boolean =
            field == this.field && value in expected
    }

    /**
     * A clause that matches if a key-value pair contains the given value.
     */
    class StringContains(override val field: Field<String>, override val expected: Any?): FieldComparison {
        override fun test(field: Field<*>, value: Any?): Boolean =
            field == this.field && value.toString().contains(expected.toString())
    }

    /**
     * A clause that matches if a key-value pair contains the given value.
     */
    class CollectionContains<F>(override val field: Field<Collection<F>>, override val expected: F): FieldComparison {
        override fun test(field: Field<*>, value: Any?): Boolean =
            field == this.field && value is Collection<*> && expected in value
    }

    /**
     * Sealed interface for clauses that compare a field's value against an expected value using
     * their natural ordering.
     */
    sealed interface RangeComparison<F: Comparable<F>>: FieldComparison {
        override val field: Field<F>
        override val expected: F

        @Suppress("UNCHECKED_CAST")
        fun compareToExpected(value: Any?): Int =
            (value as F).compareTo(expected)
    }

    /**
     * A clause that matches if a key-value pair is strictly greater than the given value.
     */
    class GreaterThan<F: Comparable<F>>(override val field: Field<F>, override val expected: F): RangeComparison<F> {
        override fun test(field: Field<*>, value: Any?): Boolean =
            field == this.field && value != null && compareToExpected(value) > 0
    }

    /**
     * A clause that matches if a key-value pair is greater than or equal to the given value.
     */
    class GreaterThanOrEqualTo<F: Comparable<F>>(override val field: Field<F>, override val expected: F): RangeComparison<F> {
        override fun test(field: Field<*>, value: Any?): Boolean =
            field == this.field && value != null && compareToExpected(value) >= 0
    }

    /**
     * A clause that matches if a key-value pair is strictly less than the given value.
     */
    class LessThan<F: Comparable<F>>(override val field: Field<F>, override val expected: F): RangeComparison<F> {
        override fun test(field: Field<*>, value: Any?): Boolean =
            field == this.field && value != null && compareToExpected(value) < 0
    }

    /**
     * A clause that matches if a key-value pair is less than or equal to the given value.
     */
    class LessThanOrEqualTo<F: Comparable<F>>(override val field: Field<F>, override val expected: F): RangeComparison<F> {
        override fun test(field: Field<*>, value: Any?): Boolean =
            field == this.field && value != null && compareToExpected(value) <= 0
    }

    sealed interface LogicalGrouping: Predicate {
        val clauses: Collection<Predicate>
    }

    /**
     * A query that matches if all the clauses match.
     */
    class And(override val clauses: List<Predicate>): LogicalGrouping

    /**
     * A query that matches if any of the clauses match.
     */
    class Or(override val clauses: List<Predicate>): LogicalGrouping

    /**
     * A query that matches if [predicate] does not match.
     */
    class Not(val predicate: Predicate): Predicate

}
