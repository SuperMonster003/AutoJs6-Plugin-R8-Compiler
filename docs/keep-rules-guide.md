# AutoJs6 R8 keep rules: practical recipes

Languages: English (current) | [简体中文](keep-rules-guide-zh-Hans.md)

This guide is for scripts that call `runtime.loadJarWithR8(...)`. It explains the smallest useful
rules for the patterns that R8 cannot discover reliably from bytecode alone: script-side
`Packages` access, reflection, JNI, serialization, and a public library API.

The examples use `com.example.plugin`. Replace that package and every class/member name with the
names from your own program JAR. Start narrow, run your real script tests, and widen a rule only
when the runtime contract actually requires it.

> Keep rules preserve runtime behavior; they are not a security boundary. Only compile and load
> JARs you trust.

## 1. Minimal starting point for an AutoJs6 script

JavaScript access through `Packages.com.example.plugin.Entry` is reflective from R8's point of
view. R8 cannot see that string-like lookup as an ordinary JVM reference, so preserve the entry
class and the members the script calls:

```proguard
-keep class com.example.plugin.Entry {
    public <init>();
    public *;
}
```

If the script only calls one static method, keep that exact surface instead:

```proguard
-keep class com.example.plugin.Entry {
    public static java.lang.String run(java.lang.String);
}
```

Use `*;` only when the script genuinely needs every field and method. A narrower member list lets
R8 remove more unreachable implementation code.

## 2. Package-wide script API

For a small JAR whose entire public package is intentionally script-facing, preserve all public
classes and public/protected members in that package and its subpackages:

```proguard
-keep public class com.example.plugin.api.** {
    public protected *;
}
```

This is a convenient initial rule, but it is intentionally broad. Prefer explicit entry classes
once the API stabilizes. Do not use a global `-keep class ** { *; }`: it disables nearly all useful
shrinking and obfuscation.

## 3. Reflection and runtime-composed names

When Java/Kotlin code itself calls `Class.forName(...)`, looks up members by name, or builds a class
name at runtime, keep the objects that the reflective code expects:

```proguard
-keep class com.example.plugin.internal.ReflectiveTarget {
    public <init>();
    public java.lang.String execute();
}
```

If only the class name must remain stable while ordinary bytecode references keep the class alive,
use:

```proguard
-keepnames class com.example.plugin.internal.ReflectiveTarget
```

`-keepnames` permits shrinking. It therefore does **not** rescue a class that has no reachable
bytecode reference; use `-keep` when the reflective lookup is the only reference.

For reflection driven by runtime annotations, preserve both the annotation attribute and the
annotated members/classes that your scanner expects:

```proguard
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations
-keep @interface com.example.plugin.api.ScriptCallable
-keep @com.example.plugin.api.ScriptCallable class * { *; }
-keepclassmembers class * {
    @com.example.plugin.api.ScriptCallable <methods>;
}
```

## 4. JNI methods and descriptor types

Native registration often depends on the original class/method names and exact JVM descriptors.
Preserve the bridge class and all native methods, including classes mentioned by their parameter
and return descriptors:

```proguard
-keep class com.example.plugin.NativeBridge
-keepclassmembers,includedescriptorclasses class * {
    native <methods>;
}
```

If your native library exports a convention-based `Java_package_Class_method` symbol instead of
using `RegisterNatives`, keeping the bridge class name and native method names is mandatory. Test
every ABI you publish; a successful R8 compilation alone does not prove the native symbol can be
resolved on-device.

## 5. Java serialization

Java serialization can invoke private hooks and reconstruct fields without ordinary call sites.
Keep stable serializable class names, hook methods, and any fields required by the serialized form:

```proguard
-keepnames class * implements java.io.Serializable
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

-keep class com.example.plugin.model.StoredState {
    <fields>;
}
```

For a long-lived persisted format, list the serialized model classes and fields explicitly. Broad
package rules can conceal accidental compatibility breaks.

## 6. Public library surface with optimizable internals

If callers compile against an API JAR but the implementation JAR is the R8 program input, retain
the externally callable API while allowing private implementation details to shrink and obfuscate:

```proguard
-keep public class com.example.plugin.api.** {
    public protected *;
}
-keep public interface com.example.plugin.api.** {
    public *;
}
-keep public enum com.example.plugin.api.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
```

Compile-time classpath JARs passed to the three- or five-argument overload are reference-only. They
are not merged into the output and are not loaded automatically at runtime.

## 7. Useful attributes

Keep only metadata your runtime behavior consumes. Typical examples are:

```proguard
-keepattributes Signature,InnerClasses,EnclosingMethod
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations
-keepattributes RuntimeVisibleParameterAnnotations,RuntimeInvisibleParameterAnnotations
```

`Signature` matters to frameworks that inspect generic types. Annotation attributes matter only
when code reads those annotations at runtime. Source-level metadata such as line numbers may help
diagnostics but does not by itself keep a class or member alive.

## 8. Warnings from optional references

Use `-dontwarn` only for a dependency that is deliberately optional and whose missing code path is
never executed in your supported environment:

```proguard
-dontwarn com.example.optional.**
```

This suppresses a diagnostic; it does not add the dependency or make a missing class safe to use.
Prefer supplying the correct compile-time classpath JAR whenever the reference is real.

## 9. Rules accepted by protocol 1.0

The plugin applies a narrower, fail-closed admission policy than a general ProGuard/R8 command
line. Protocol 1.0 accepts these directive families:

```text
-keep
-keepnames
-keepclassmembers
-keepclassmembernames
-keepclasseswithmembers
-keepclasseswithmembernames
-keeppackagenames
-if
-keepattributes
-dontwarn
-dontnote
```

Keep directives may use only these modifiers:

```text
allowshrinking, allowoptimization, allowobfuscation,
allowaccessmodification, includedescriptorclasses, includecode
```

File access, includes, input/output redirection, imported mappings, dictionaries, print directives,
global profile switches, repackaging, and assumptions are rejected. Examples that are intentionally
forbidden include `-include`, `@file`, `-injars`, `-libraryjars`, `-applymapping`,
`-printmapping`, `-obfuscationdictionary`, `-dontshrink`, `-dontoptimize`, `-dontobfuscate`,
`-repackageclasses`, and `-assumenosideeffects`. The host/provider owns all inputs, outputs, and the
fixed full-release profile.

Rule files must be strict UTF-8 without a BOM, must use LF line endings, and must end in a final LF.
Line continuations are forbidden. The current limits are 16 keep-rule files, 32 consumer-rule files,
256 KiB per file, 2 MiB in total, and 16 KiB per line.

## 10. Debugging a missing class or method

When the original JAR works but the R8-loaded result throws a missing-class/member error:

1. Confirm the script uses the intended fully qualified name and method descriptor.
2. Add an exact `-keep class ... { ...; }` rule for the failing script/reflection entry.
3. Include descriptor classes for JNI or reflectively inspected method signatures.
4. Preserve runtime annotations or serialization hooks only when the framework requires them.
5. Re-run the real script path. Changing rule bytes creates a different R8 cache identity, so the
   host will not reuse the previous generation.
6. Narrow any temporary package-wide rule after the runtime path passes.

A useful diagnostic rule is a temporary exact class-wide keep:

```proguard
-keep class com.example.plugin.Suspect { *; }
```

If that fixes the problem, reduce `*;` to the constructor, fields, and methods that are actually
accessed. If it does not, inspect classpath/runtime dependency availability instead of widening
rules indefinitely.

## Complete starter file

The following combines the most common AutoJs6-facing patterns. Save it as UTF-8 without BOM and
ensure the file ends with a newline:

```proguard
# Script entry points
-keep class com.example.plugin.api.ScriptApi { public *; }

# Runtime-composed/reflected target
-keep class com.example.plugin.internal.ReflectiveTarget { *; }

# JNI bridge and descriptor types
-keep class com.example.plugin.NativeBridge
-keepclassmembers,includedescriptorclasses class * {
    native <methods>;
}

# Java serialization hooks and persisted model
-keepnames class * implements java.io.Serializable
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object readResolve();
}
-keep class com.example.plugin.model.StoredState { <fields>; }

# Metadata read at runtime
-keepattributes Signature,RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations
```

The authoritative admission behavior is implemented by
`plugin-api/r8-compiler-api/src/main/java/org/autojs/plugin/r8compiler/api/R8RulePolicy.kt`.
If a future protocol version expands the rule surface, treat that version's frozen contract as the
source of truth.
