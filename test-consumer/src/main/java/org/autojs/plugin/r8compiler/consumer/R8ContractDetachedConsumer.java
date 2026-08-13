package org.autojs.plugin.r8compiler.consumer;

import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import org.autojs.plugin.protocol.wire.TaggedWireProtocol;
import org.autojs.plugin.protocol.wire.TaggedWireWriter;
import org.autojs.plugin.r8compiler.api.IR8CompilerCallback;
import org.autojs.plugin.r8compiler.api.IR8CompilerProvider;
import org.autojs.plugin.r8compiler.api.IR8CompilerSession;
import org.autojs.plugin.r8compiler.api.R8CompilerContract;
import org.autojs.plugin.r8compiler.api.R8CompilerFamily;
import org.autojs.plugin.r8compiler.api.R8CompilerIntent;
import org.autojs.plugin.r8compiler.api.R8FallbackPolicy;
import org.autojs.plugin.r8compiler.api.R8Sha256;

/**
 * Deliberately plain Java: the distribution gate compiles this file outside the source tree with
 * android.jar and only the classes.jar payloads extracted from the two candidate AARs.
 */
public final class R8ContractDetachedConsumer {
    private R8ContractDetachedConsumer() {
    }

    public static void verifyBinderAbi(
            IR8CompilerProvider provider,
            IR8CompilerCallback callback,
            ParcelFileDescriptor input,
            ParcelFileDescriptor output
    ) throws RemoteException {
        byte[] info = provider.getCompilerInfo();
        byte[] capabilities = provider.getCapabilities();
        IR8CompilerSession session = provider.openSession(new byte[] { 1 }, input, output, callback);
        session.cancel();
        session.close();
        callback.onStarted(info);
        callback.onProgress(capabilities);
        callback.onCompleted(info);
        callback.onFailed(capabilities);
        callback.onCancelled(info);
    }

    public static String verifyLinkage() {
        if (!"org.autojs.plugin.R8_COMPILER".equals(R8CompilerContract.SERVICE_ACTION)) {
            throw new IllegalStateException("Unexpected R8 service action");
        }
        if (R8CompilerFamily.R8.getWireCode() != 1
                || R8CompilerIntent.R8_EXPLICIT.getWireCode() != 1
                || R8FallbackPolicy.NONE.getWireCode() != 1) {
            throw new IllegalStateException("Unexpected R8 wire identity");
        }
        if (TaggedWireProtocol.MAGIC != 0x414A3657) {
            throw new IllegalStateException("Unexpected TaggedWire identity");
        }
        if (new TaggedWireWriter(1, 1, 0, new org.autojs.plugin.protocol.wire.TaggedWireLimits(4096, 1024, 8))
                .int32(1, 1, true)
                .encode().length == 0) {
            throw new IllegalStateException("TaggedWire writer linkage failed");
        }
        if (R8Sha256.Companion.digest(new byte[] { 1 }).toByteArray().length != 32) {
            throw new IllegalStateException("R8 digest linkage failed");
        }

        Class<?>[] binderContracts = {
                R8CompilerContract.class,
                IR8CompilerProvider.class,
                IR8CompilerCallback.class,
                IR8CompilerSession.class,
        };
        if (binderContracts.length != 4) {
            throw new AssertionError("Unexpected Binder contract count");
        }
        return R8CompilerContract.ENGINE_ID;
    }
}
