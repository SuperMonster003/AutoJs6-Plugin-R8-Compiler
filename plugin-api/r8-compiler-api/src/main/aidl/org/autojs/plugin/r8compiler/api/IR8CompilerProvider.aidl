package org.autojs.plugin.r8compiler.api;

import android.os.ParcelFileDescriptor;
import org.autojs.plugin.r8compiler.api.IR8CompilerCallback;
import org.autojs.plugin.r8compiler.api.IR8CompilerSession;

interface IR8CompilerProvider {
    byte[] getCompilerInfo();
    byte[] getCapabilities();

    // The caller retains and closes its local PFD instances after the transaction returns. After
    // a successful dispatch, the provider exclusively owns its received duplicates, keeps them
    // only until its one terminal callback, and closes them on every terminal or setup-failure
    // path. The input and output descriptors must not alias the same endpoint. Dispatch is never
    // retried and is never redirected to D8.
    IR8CompilerSession openSession(
        in byte[] request,
        in ParcelFileDescriptor inputBundleFd,
        in ParcelFileDescriptor outputBundleFd,
        IR8CompilerCallback callback
    );

    // Protocol 1.1 append-only extension. Existing transaction IDs above are unchanged. Retrace
    // uses the same descriptor-ownership and exactly-one-terminal callback law as compilation;
    // every callback byte array carries a retrace-specific schema.
    byte[] getRetraceCapabilities();
    IR8CompilerSession openRetraceSession(
        in byte[] request,
        in ParcelFileDescriptor inputBundleFd,
        in ParcelFileDescriptor outputStackTraceFd,
        IR8CompilerCallback callback
    );
}
