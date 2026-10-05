package io.fixwire.kotlin

import io.fixwire.FakeIngest
import io.fixwire.Fixwire
import io.fixwire.Hub
import io.fixwire.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class FixwireContextTest {
  private lateinit var ingest: FakeIngest

  @BeforeTest
  fun start() {
    ingest = FakeIngest()
  }

  @AfterTest
  fun stop() {
    ingest.close()
  }

  @Test
  fun eachCoroutineKeepsItsHubAcrossThreads() {
    val options = Options()
    options.tracesSampleRate = 1.0
    val base = ingest.hub(options)
    runBlocking {
      (1..3)
        .map { n ->
          val hub = base.copy()
          async(Dispatchers.Default + FixwireContext(hub)) {
            Fixwire.setTag("order", "ord_$n")
            delay(10) // resumes on any thread
            withContext(Dispatchers.IO) {
              assertSame(hub, Hub.current())
              withSpan("charge $n", "task") {
                delay(5)
                Fixwire.captureMessage("charged ord_$n")
              }
            }
          }
        }.awaitAll()
    }
    assertSame(Hub.main(), Hub.current(), "the bindings were undone")
    base.flush(5000)

    val tags =
      FakeIngest
        .logRecords(ingest.requests("/v1/logs"))
        .map { FakeIngest.kv(it["attributes"]) }
        .associate { (it["fixwire.transaction"] as String) to (it["fixwire.tags"] as Map<*, *>)["order"] }
    assertEquals(mapOf("charge 1" to "ord_1", "charge 2" to "ord_2", "charge 3" to "ord_3"), tags)
    assertEquals(3, FakeIngest.spans(ingest.requests("/v1/traces")).size)
  }
}
