package org.autojs.plugin.r8compiler.consumer;

import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import org.autojs.plugin.protocol.wire.TaggedWireProtocol;
import org.autojs.plugin.r8compiler.api.IR8CompilerCallback;
import org.autojs.plugin.r8compiler.api.IR8CompilerProvider;
import org.autojs.plugin.r8compiler.api.IR8CompilerSession;
import org.autojs.plugin.r8compiler.api.R8CompilerContract;
import org.autojs.plugin.r8compiler.api.R8RetraceCapabilityFingerprint;
import org.autojs.plugin.r8compiler.api.R8RetraceInputLayout;
import org.autojs.plugin.r8compiler.api.R8RetraceOutputLayout;

/**
 * Plain-Java G10 linkage probe. The freeze verifier compiles it in a temporary directory with
 * android.jar and only the classes.jar payloads extracted from the two staged AARs.
 */
public final class R8RetraceContractDetachedConsumer {
    private R8RetraceContractDetachedConsumer() {
    }

    public static void verifyBinderAbi(
            IR8CompilerProvider provider,
            IR8CompilerCallback callback,
            ParcelFileDescriptor input,
            ParcelFileDescriptor output
    ) throws RemoteException {
        byte[] info = provider.getCompilerInfo();
        byte[] compileCapabilities = provider.getCapabilities();
        byte[] retraceCapabilities = provider.getRetraceCapabilities();
        IR8CompilerSession compile = provider.openSession(
                new byte[] { 1 }, input, output, callback);
        IR8CompilerSession retrace = provider.openRetraceSession(
                new byte[] { 1, 1 }, input, output, callback);
        compile.cancel();
        compile.close();
        retrace.cancel();
        retrace.close();
        callback.onStarted(info);
        callback.onProgress(compileCapabilities);
        callback.onCompleted(retraceCapabilities);
        callback.onFailed(compileCapabilities);
        callback.onCancelled(info);
    }

    public static String verifyLinkage() {
        if (R8CompilerContract.INSTANCE.getPROTOCOL_V1_1().getMajor() != 1
                || R8CompilerContract.INSTANCE.getPROTOCOL_V1_1().getMinor() != 1) {
            throw new IllegalStateException("Unexpected retrace protocol version");
        }
        if (R8RetraceInputLayout.MAPPING_METADATA_AND_STACK_BUNDLE_V1.getWireCode() != 1
                || R8RetraceOutputLayout.UTF8_LF_STACK_TRACE_V1.getWireCode() != 1) {
            throw new IllegalStateException("Unexpected retrace layout identity");
        }
        if (TaggedWireProtocol.MAGIC != 0x414A3657
                || R8RetraceCapabilityFingerprint.class.getName().isEmpty()) {
            throw new IllegalStateException("Unexpected contract linkage");
        }
        return R8CompilerContract.MAPPING_FORMAT_ID;
    }
}
