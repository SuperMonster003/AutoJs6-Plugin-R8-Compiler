package io.github.supermonster003.autojs6.plugin.r8compiler

import java.util.concurrent.atomic.AtomicReference

/** Process-wide, identity-based ownership for the one session advertised by protocol 1.0. */
internal class SingleActiveSessionGate<T : Any> {
    private val active = AtomicReference<T?>()

    fun tryAcquire(session: T): Boolean = active.compareAndSet(null, session)

    fun release(session: T): Boolean = active.compareAndSet(session, null)
}
