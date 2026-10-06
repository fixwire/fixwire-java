# Order worker (Kotlin coroutines)

```sh
FIXWIRE_DSN=https://<key>@<host> ./gradlew :examples:order-worker:run   # from the repository root
```

Three workers take orders from a channel. Each order is handled in its own
coroutine, which may resume on any thread after it suspends. One order asks
for a SKU the warehouse doesn't stock; it fails and is reported, and the
others go on.

What arrives in Fixwire:

- **The failed order** (`OutOfStockException`) with *its own* user
  (`user-2`) and tag (`order: ord_2`), though three workers ran at once on
  shared threads, and the `reserving sku_9` breadcrumb.
- **A trace per order**: `handle order`, with `charge ord_…` under it when
  the order got that far; the failed order's span is marked failed and its
  error is linked to it.

How it is wired, in `OrderWorker.kt`:

```kotlin
Fixwire.init {
    it.release = "order-worker@1.0.0"
    it.tracesSampleRate = 1.0
}

for (order in queue) {
    // Each order gets its own hub: what it sets stays with it, across threads.
    launch(FixwireContext()) { handle(order) }
}

suspend fun handle(order: Order) {
    Fixwire.setUser(User(order.customer))
    Fixwire.setTag("order", order.id)
    withSpan("handle order", "queue.process") { … }
}
```

Without `FixwireContext`, coroutines on the same thread would share (and
overwrite) one another's user and tags.
