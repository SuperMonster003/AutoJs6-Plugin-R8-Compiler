package org.autojs.fixture.r8compiler.g7;

public final class G7NativeBridge {
    private G7NativeBridge() {
    }

    public static void load(String absolutePath) {
        System.load(absolutePath);
    }

    public static native long nativeRoundTrip(long value);

    public static native String nativeStaticMessage();
}
