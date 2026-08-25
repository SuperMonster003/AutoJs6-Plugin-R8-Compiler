package io.github.supermonster003.autojs6.plugin.r8compiler

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import io.github.supermonster003.autojs6.plugin.r8compiler.service.HostCallerVerifier
import io.github.supermonster003.autojs6.plugin.r8compiler.service.OwnedParcelFileDescriptors
import io.github.supermonster003.autojs6.plugin.r8compiler.service.RemoteR8CompileSession
import io.github.supermonster003.autojs6.plugin.r8compiler.service.SerialCallbackLane
import org.autojs.plugin.r8compiler.api.IR8CompilerCallback
import org.autojs.plugin.r8compiler.api.IR8CompilerProvider
import org.autojs.plugin.r8compiler.api.IR8CompilerSession
import org.autojs.plugin.r8compiler.api.R8CompilerCodec
import org.autojs.plugin.r8compiler.api.R8CompilerContract
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

private val processSessionGate = SingleActiveSessionGate<RemoteR8CompileSession>()
private val processWorkspaceRecovery = ProcessWorkspaceRecovery()

class R8CompilerService : Service() {
    private lateinit var callerVerifier: HostCallerVerifier
    private lateinit var worker: ExecutorService
    private lateinit var scheduler: ScheduledExecutorService
    private lateinit var callbackLane: SerialCallbackLane
    private val sessions = ConcurrentHashMap.newKeySet<RemoteR8CompileSession>()
    private val runtimeLibraries by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RuntimeLibrarySet.discover(applicationContext)
    }
    private val capabilities by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        R8CompilerRuntime.capabilities(runtimeLibraries)
    }

    override fun onCreate() {
        super.onCreate()
        processWorkspaceRecovery.ensureRecovered { PrivateSessionWorkspace.recoverStale(applicationContext) }
        callerVerifier = HostCallerVerifier(this)
        worker = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "r8-compiler-worker").apply { isDaemon = true }
        }
        scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "r8-compiler-deadline").apply { isDaemon = true }
        }
        callbackLane = SerialCallbackLane()
    }

    override fun onBind(intent: Intent?): IBinder? =
        binder.takeIf { intent?.action == R8CompilerContract.SERVICE_ACTION }

    override fun onDestroy() {
        sessions.toList().forEach(RemoteR8CompileSession::serviceDestroyed)
        sessions.clear()
        worker.shutdownNow()
        scheduler.shutdownNow()
        callbackLane.close()
        super.onDestroy()
    }

    private val binder = object : IR8CompilerProvider.Stub() {
        override fun getCompilerInfo(): ByteArray {
            callerVerifier.enforceAllowedCaller()
            return R8CompilerCodec.encodeInfo(R8CompilerRuntime.info(this@R8CompilerService))
        }

        override fun getCapabilities(): ByteArray {
            callerVerifier.enforceAllowedCaller()
            return R8CompilerCodec.encodeCapabilities(this@R8CompilerService.capabilities)
        }

        override fun openSession(
            request: ByteArray?,
            inputBundleFd: ParcelFileDescriptor?,
            outputBundleFd: ParcelFileDescriptor?,
            callback: IR8CompilerCallback?,
        ): IR8CompilerSession {
            val ownerUid = try {
                callerVerifier.enforceAllowedCaller()
            } catch (error: Throwable) {
                OwnedParcelFileDescriptors.closeIncoming(inputBundleFd, outputBundleFd)
                throw error
            }
            if (request == null || inputBundleFd == null || outputBundleFd == null || callback == null) {
                OwnedParcelFileDescriptors.closeIncoming(inputBundleFd, outputBundleFd)
                throw IllegalArgumentException("R8 compiler session arguments must not be null")
            }
            val descriptors = OwnedParcelFileDescriptors.duplicateAndValidate(inputBundleFd, outputBundleFd)
            val session = try {
                RemoteR8CompileSession(
                    context = applicationContext,
                    ownerUid = ownerUid,
                    requestMetadata = request.copyOf(),
                    descriptors = descriptors,
                    callback = callback,
                    capabilities = this@R8CompilerService.capabilities,
                    runtimeLibraries = runtimeLibraries,
                    callerVerifier = callerVerifier,
                    worker = worker,
                    scheduler = scheduler,
                    callbackLane = callbackLane,
                    onFinished = { finished ->
                        processSessionGate.release(finished)
                        sessions.remove(finished)
                    },
                )
            } catch (error: Throwable) {
                descriptors.close()
                throw error
            }
            sessions += session
            if (processSessionGate.tryAcquire(session)) session.start() else session.rejectBusy()
            return session
        }
    }
}
