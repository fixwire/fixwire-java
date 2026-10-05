package io.fixwire.kotlin

import io.fixwire.Hub
import io.fixwire.Span
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Carries a Fixwire hub with a coroutine: whichever thread runs it, Fixwire's calls use this hub
 * (its scope, its current span). Give each request or job its own copy:
 *
 * ```
 * launch(FixwireContext()) {
 *     Fixwire.setTag("order", order.id)
 *     charge(order) // errors captured here carry the tag, on any thread
 * }
 * ```
 */
public class FixwireContext(
    /** The hub; by default a copy of the current one. */
    public val hub: Hub = Hub.current().copy(),
) : AbstractCoroutineContextElement(Key),
    ThreadContextElement<Hub.Binding> {
    /** The key of the element in a coroutine context. */
    public companion object Key : CoroutineContext.Key<FixwireContext>

    override fun updateThreadContext(context: CoroutineContext): Hub.Binding = hub.bind()

    override fun restoreThreadContext(
        context: CoroutineContext,
        oldState: Hub.Binding,
    ) {
        oldState.close()
    }
}

/**
 * Runs [block] in a span under the current one, ended (and failed, when [block] throws) when it
 * returns. The span stays current across suspensions when the coroutine has a [FixwireContext].
 */
public suspend fun <T> withSpan(
    name: String,
    op: String,
    block: suspend (Span) -> T,
): T {
    val hub = Hub.current()
    val span = hub.spanBuilder(name).op(op).start()
    return try {
        withContext(FixwireContext(hub)) { block(span) }
    } catch (e: Throwable) {
        span.setError(e)
        throw e
    } finally {
        span.close()
    }
}
