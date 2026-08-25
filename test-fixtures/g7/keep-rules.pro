-keep class org.autojs.fixture.r8compiler.g7.G7RuntimeFixture { public *; }
-keep class org.autojs.fixture.r8compiler.g7.G7ReflectiveTarget { public <init>(); public java.lang.String message(); }
-keep class org.autojs.fixture.r8compiler.g7.G7DynamicTarget { public <init>(); public java.lang.String value(); }
-keep class org.autojs.fixture.r8compiler.g7.G7NativeBridge { public *; native <methods>; }
-keepclassmembers class * implements java.io.Serializable {
    private static final long serialVersionUID;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object readResolve();
}
-keep,allowobfuscation class org.autojs.fixture.r8compiler.g7.G7ObfuscatedCrash { *; }
-dontwarn java.lang.Object
