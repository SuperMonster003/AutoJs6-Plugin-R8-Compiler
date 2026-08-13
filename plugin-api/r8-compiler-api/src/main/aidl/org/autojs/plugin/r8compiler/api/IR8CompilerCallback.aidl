package org.autojs.plugin.r8compiler.api;

oneway interface IR8CompilerCallback {
    // At most one started event. Progress sequence values are strictly increasing and, when
    // started exists, greater than its sequence. Exactly one terminal event follows, then silence.
    void onStarted(in byte[] metadata);
    void onProgress(in byte[] progress);
    void onCompleted(in byte[] result);
    void onFailed(in byte[] error);
    void onCancelled(in byte[] cancellation);
}
