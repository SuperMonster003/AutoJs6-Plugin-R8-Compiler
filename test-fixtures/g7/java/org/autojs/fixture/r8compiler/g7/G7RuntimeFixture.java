package org.autojs.fixture.r8compiler.g7;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

public final class G7RuntimeFixture {
    private G7RuntimeFixture() {
    }

    public static String reflectiveCall() throws Exception {
        Class<?> type = Class.forName("org.autojs.fixture.r8compiler.g7.G7ReflectiveTarget");
        Object target = type.getDeclaredConstructor().newInstance();
        return (String) type.getDeclaredMethod("message").invoke(target);
    }

    public static String dynamicCall(String simpleName) throws Exception {
        Class<?> type = Class.forName("org.autojs.fixture.r8compiler.g7." + simpleName);
        Object target = type.getDeclaredConstructor().newInstance();
        return (String) type.getDeclaredMethod("value").invoke(target);
    }

    public static String serializationRoundTrip(String label, int count) throws Exception {
        G7SerializableState source = new G7SerializableState(label, count);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(source);
        }
        G7SerializableState restored;
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            restored = (G7SerializableState) input.readObject();
        }
        return restored.getLabel() + ":" + restored.getCount();
    }

    public static String scriptEntry(String name) {
        return "hello," + name;
    }

    public static void crashForRetrace(String marker) {
        G7ObfuscatedCrash.explode(marker);
    }
}
