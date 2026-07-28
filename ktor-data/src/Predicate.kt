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
    class Equals(val field: Field<*>, val value: Any?): FieldClause {
        override fun test(field: Field<*>, value: Any?): Boolean =
            field == this.field && value == this.value
    }

    /**
     * A clause that matches if a key-value pair is one of the given values.
     */
    class IsOneOf<F>(val field: Field<F>, val values: Iterable<F>): FieldClause {
        override fun test(field: Field<*>, value: Any?): Boolean =
            field == this.field && value in this.values
    }

    /**
     * A clause that matches if a key-value pair contains the given value.
     */
    class StringContains(val field: Field<String>, val value: Any?): FieldClause {
        override fun test(field: Field<*>, value: Any?): Boolean =
            field == this.field && value.toString().contains(this.value.toString())
    }

    /**
     * A clause that matches if a key-value pair contains the given value.
     */
    class CollectionContains<F>(val field: Field<Collection<F>>, val value: Collection<F>): FieldClause {
        override fun test(field: Field<*>, value: Any?): Boolean =
            field == this.field && value in this.value
    }

    sealed interface LogicalGrouping: Predicate {
        val clauses: Collection<Predicate>
    }

    /**
     * A query that matches if all the clauses match.
     */
    class And(override val clauses: List<Predicate>): LogicalGrouping {
        constructor(vararg clauses: Predicate): this(clauses.toList())
    }

    /**
     * A query that matches if any of the clauses match.
     */
    class Or(override val clauses: List<Predicate>): LogicalGrouping {
        constructor(vararg clauses: Predicate): this(clauses.toList())
    }

}
