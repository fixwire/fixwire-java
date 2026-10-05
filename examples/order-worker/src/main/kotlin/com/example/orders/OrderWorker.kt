package com.example.orders

import io.fixwire.Fixwire
import io.fixwire.User
import io.fixwire.kotlin.FixwireContext
import io.fixwire.kotlin.withSpan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger

/*
 * A worker handling orders in coroutines, reporting to Fixwire: each order runs with its own hub
 * (its user and tags stay with it on whichever thread runs it), its own trace, and a failed order
 * is reported without stopping the others.
 *
 * ```
 * FIXWIRE_DSN=https://<key>@<host> ./gradlew :examples:order-worker:run
 * ```
 */
data class Order(
    val id: String,
    val customer: String,
    val sku: String,
)

/** What the warehouse answers for a SKU it doesn't stock. */
class OutOfStockException(
    sku: String,
) : Exception("out of stock: $sku")

val orders =
    listOf(
        Order("ord_1", "user-1", "sku_1"),
        Order("ord_2", "user-2", "sku_9"),
        Order("ord_3", "user-3", "sku_2"),
        Order("ord_4", "user-4", "sku_1"),
    )

fun main() {
    val failed = runWorker(dsn = null) // the DSN comes from FIXWIRE_DSN
    kotlin.system.exitProcess(if (failed > 0) 1 else 0)
}

/** Handles [orders] with three workers; the number that failed. */
fun runWorker(dsn: String?): Int {
    Fixwire.init {
        it.dsn = dsn
        it.release = "order-worker@1.0.0"
        it.tracesSampleRate = 1.0
    }
    try {
        return runBlocking(Dispatchers.Default) { handleAll(orders, workers = 3) }
    } finally {
        Fixwire.close(5000)
    }
}

suspend fun handleAll(
    orders: List<Order>,
    workers: Int,
): Int {
    val queue = Channel<Order>(Channel.UNLIMITED)
    orders.forEach { queue.trySend(it) }
    queue.close()
    val failed = AtomicInteger() // workers run on several threads at once
    coroutineScope {
        repeat(workers) {
            launch {
                for (order in queue) {
                    // Each order gets its own hub: what it sets stays with it, across threads.
                    launch(FixwireContext()) {
                        if (!handle(order)) failed.incrementAndGet()
                    }.join()
                }
            }
        }
    }
    return failed.get()
}

/** Handles one order in its own trace; false when it failed (and was reported). */
suspend fun handle(order: Order): Boolean {
    Fixwire.setUser(User(order.customer))
    Fixwire.setTag("order", order.id)
    return withSpan("handle order", "queue.process") {
        try {
            reserve(order.sku)
            withSpan("charge ${order.id}", "payment") { delay(5) }
            true
        } catch (e: OutOfStockException) {
            it.setError(e)
            Fixwire.captureException(e)
            false
        }
    }
}

suspend fun reserve(sku: String) {
    Fixwire.addBreadcrumb("warehouse", "reserving $sku")
    delay(10) // the warehouse answers; the coroutine may resume on another thread
    if (sku == "sku_9") throw OutOfStockException(sku)
}
