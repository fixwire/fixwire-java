package com.example.orders

import io.fixwire.FakeIngest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Runs the worker against a fake ingest and checks what Fixwire receives. */
class OrderWorkerTest {
    @Test
    fun reportsTheFailedOrderWithItsOwnUserAndTrace() {
        FakeIngest().use { ingest ->
            assertEquals(1, runWorker(ingest.dsn()), "sku_9 is out of stock")

            val events = FakeIngest.logRecords(ingest.requests("/v1/logs"))
            assertEquals(1, events.size)
            val failure = FakeIngest.kv(events[0]["attributes"])
            assertEquals("com.example.orders.OutOfStockException", failure["exception.type"])
            // The order's own user and tag, though three workers ran at once.
            assertEquals("user-2", failure["user.id"])
            assertEquals(mapOf("order" to "ord_2"), failure["fixwire.tags"])

            // A trace per order: its handling, and the charge when it got that far.
            val spans = FakeIngest.spans(ingest.requests("/v1/traces"))
            val byName = spans.groupBy { it["name"] as String }.mapValues { it.value.size }
            assertEquals(4, byName["handle order"], byName.toString())
            assertEquals(4, spans.map { it["traceId"] }.toSet().size)
            assertEquals(3, spans.count { (it["name"] as String).startsWith("charge ") })
            val failedSpan = spans.single { it["traceId"] == events[0]["traceId"] && it["name"] == "handle order" }
            assertEquals(2, (failedSpan["status"] as Map<*, *>)["code"])
        }
    }
}
