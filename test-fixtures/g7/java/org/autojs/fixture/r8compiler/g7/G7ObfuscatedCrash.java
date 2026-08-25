package org.autojs.fixture.r8compiler.g7;

public final class G7ObfuscatedCrash {
    private G7ObfuscatedCrash() {
    }

    public static void explode(String marker) {
        throw new IllegalStateException(marker + ":g7-obfuscated-crash");
    }
}
