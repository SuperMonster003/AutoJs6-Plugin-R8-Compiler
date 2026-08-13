package org.autojs.plugin.r8compiler.api;

oneway interface IR8CompilerSession {
    // Both operations are idempotent; neither authorizes retry or fallback.
    void cancel();
    void close();
}
