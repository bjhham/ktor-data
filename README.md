Ktor Data
=========

This is an experimental prototype for an asynchronous persistence abstraction layer.

The goals of this project are to:
1. Act as a bridge between serializable types and the persistence layer.
2. Provide a seamless API client for HTTP back-ends or local storage.
3. Become a new standard for reactive programming persistence.
4. Be as generic and mockable as possible for testing or isolated execution.

Here is an example of how to use the [ListRepository](ktor-data/src/ListRepository.kt):

```kotlin
val examples = ListRepository<Example>()
val first = examples.createAndGet(Example(name = "First"))
val second = examples.createAndGet(Example(name = "Second"))
assertEquals(listOf(first, second), examples.all().list())

val updated = first.copy(name = "Updated")
examples.update(updated)
assertEquals(updated, examples.get(first.id))

examples.delete(second.id)
assertEquals(listOf(updated), examples.all().list())
```

### Implementations

- [ObservableRepository](ktor-data/src/ObservableRepository.kt) for real-time subscriptions for APIs that support it.
- [ExposedR2dbcRepository](ktor-data-exposed-r2dbc/src/ExposedR2dbcRepository.kt) that illustrates
  how we can adapt this library for [Exposed](https://github.com/JetBrains/Exposed).

