package io.github.supermonster003.autojs6.plugin.r8compiler.service

/** Common process-gate lifecycle shared by protocol 1.0 compile and protocol 1.1 retrace. */
internal interface RemoteR8ServiceSession {
    fun start()
    fun rejectBusy()
    fun serviceDestroyed()
}
