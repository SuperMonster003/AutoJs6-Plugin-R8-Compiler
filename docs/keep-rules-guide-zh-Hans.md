# AutoJs6 R8 keep 规则实用指南

语言: [English](keep-rules-guide.md) | 简体中文 (当前)

本指南面向调用 `runtime.loadJarWithR8(...)` 的脚本作者，给出几类 R8 无法单靠字节码可靠推断的运行时入口配方：脚本端 `Packages` 访问、反射与动态类名、JNI、Java 序列化，以及对外公开的库 API。

下文使用 `com.example.plugin` 作为示例。请替换为自己 program JAR 中的真实包名、类名与成员签名。建议从精确规则开始，用实际脚本路径测试，仅在运行时契约确实需要时才扩大保留范围。

> keep 规则用于保持运行时行为，并不构成安全边界。只编译和加载你信任的 JAR。

## 1. AutoJs6 脚本的最小入口

脚本通过 `Packages.com.example.plugin.Entry` 访问类，在 R8 看来属于反射调用。R8 无法从普通 JVM 引用推断这个入口，因此需要保留入口类及脚本实际调用的成员：

```proguard
-keep class com.example.plugin.Entry {
    public <init>();
    public *;
}
```

如果脚本只调用一个静态方法，应进一步收窄：

```proguard
-keep class com.example.plugin.Entry {
    public static java.lang.String run(java.lang.String);
}
```

只有脚本确实需要所有字段和方法时才使用 `*;`。规则越精确，R8 越能移除无用实现。

## 2. 面向脚本的整个 API 包

对于整个公开包都刻意设计为脚本 API 的小型 JAR，可以保留该包及子包中的公开类与 public/protected 成员：

```proguard
-keep public class com.example.plugin.api.** {
    public protected *;
}
```

这适合作为早期规则，但范围较宽。API 稳定后，优先改成明确的入口类。不要使用全局 `-keep class ** { *; }`，它会让大部分压缩与混淆失去意义。

## 3. 反射与运行时拼接类名

Java/Kotlin 代码若使用 `Class.forName(...)`、按名字查找成员，或在运行时拼接类名，就要保留被查找的对象：

```proguard
-keep class com.example.plugin.internal.ReflectiveTarget {
    public <init>();
    public java.lang.String execute();
}
```

如果普通字节码引用已经能保证类不会被移除，而你只要求类名保持不变，可以使用：

```proguard
-keepnames class com.example.plugin.internal.ReflectiveTarget
```

`-keepnames` 允许 shrinking，因此不能挽救一个完全没有可达引用的类；反射是唯一引用时应使用 `-keep`。

如果框架通过运行时注解扫描类或成员，还要同时保留注解属性与被扫描对象：

```proguard
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations
-keep @interface com.example.plugin.api.ScriptCallable
-keep @com.example.plugin.api.ScriptCallable class * { *; }
-keepclassmembers class * {
    @com.example.plugin.api.ScriptCallable <methods>;
}
```

## 4. JNI 方法与描述符类型

native 注册通常依赖原始类名、方法名和精确 JVM 描述符。应保留桥接类、所有 native 方法，以及参数/返回值描述符中出现的类型：

```proguard
-keep class com.example.plugin.NativeBridge
-keepclassmembers,includedescriptorclasses class * {
    native <methods>;
}
```

如果 native 库导出约定命名的 `Java_package_Class_method` 符号而不是使用 `RegisterNatives`，桥接类名与 native 方法名都必须保持稳定。请在每个发布 ABI 上做真实设备测试；R8 编译成功并不能证明 native 符号一定可在设备上解析。

## 5. Java 序列化

Java 序列化会调用私有 hook，并可能在没有普通调用点的情况下还原字段。应保留稳定的序列化类名、hook 方法和持久化格式需要的字段：

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

对于需要长期兼容的持久化格式，应明确列出模型类与字段。宽泛的包级规则容易掩盖意外的兼容性破坏。

## 6. 保留公共 API，允许内部实现优化

如果调用方依据 API JAR 编译，而实现 JAR 是 R8 的 program 输入，可保留外部可调用表面，同时允许私有实现继续压缩与混淆：

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

传给三参数或五参数重载的 classpath JAR 只用于编译期解析，不会合并进输出，也不会在运行时自动加载。

## 7. 运行时元数据属性

只保留运行时行为真正读取的元数据。常见组合如下：

```proguard
-keepattributes Signature,InnerClasses,EnclosingMethod
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations
-keepattributes RuntimeVisibleParameterAnnotations,RuntimeInvisibleParameterAnnotations
```

检查泛型类型的框架通常需要 `Signature`；运行时读取注解的代码需要对应 annotation 属性。保留行号等调试元数据本身不会让类或成员变得可达。

## 8. 可选依赖告警

只有当某个依赖确实可选，且缺失依赖的代码路径不会在受支持环境中执行时，才使用 `-dontwarn`：

```proguard
-dontwarn com.example.optional.**
```

它只会抑制诊断，不会添加依赖，也不会让实际执行的缺失类变得安全。真实引用应优先传入正确的编译期 classpath JAR。

## 9. 协议 1.0 接受的规则范围

插件采用比通用 ProGuard/R8 命令行更窄的 fail-closed 白名单。协议 1.0 接受：

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

keep 指令只允许下列 modifier：

```text
allowshrinking, allowoptimization, allowobfuscation,
allowaccessmodification, includedescriptorclasses, includecode
```

文件访问、include、输入输出重定向、导入 mapping、字典、print 指令、全局编译 profile 开关、重新打包与行为假设均会被拒绝。明确禁止的例子包括 `-include`、`@file`、`-injars`、`-libraryjars`、`-applymapping`、`-printmapping`、`-obfuscationdictionary`、`-dontshrink`、`-dontoptimize`、`-dontobfuscate`、`-repackageclasses` 与 `-assumenosideeffects`。输入、输出和固定的 full-release profile 均由宿主/提供者控制。

规则文件必须是无 BOM 的严格 UTF-8，使用 LF 换行，并以最后一个 LF 结尾；禁止续行。当前限制为：keep 文件最多 16 个，consumer 文件最多 32 个，单文件最大 256 KiB，总计最大 2 MiB，单行最大 16 KiB。

## 10. 排查类或方法缺失

原始 JAR 正常，而 R8 产物加载后报告类或成员缺失时：

1. 确认脚本使用的是正确的完全限定类名与方法签名。
2. 为出错的脚本/反射入口添加精确的 `-keep class ... { ...; }`。
3. JNI 或反射检查方法签名时，加入 `includedescriptorclasses`。
4. 仅在框架确实需要时保留运行时注解或序列化 hook。
5. 重跑真实脚本路径。规则字节发生变化会形成新的 R8 缓存标识，宿主不会复用旧 generation。
6. 路径通过后，收窄排障期间添加的包级规则。

可临时用下面的精确类级规则诊断：

```proguard
-keep class com.example.plugin.Suspect { *; }
```

如果它解决问题，就把 `*;` 缩减到实际访问的构造器、字段和方法；如果仍无效，应检查 classpath 或运行时依赖，而不是无限扩大 keep 范围。

## 完整起步文件

以下规则覆盖最常见的 AutoJs6 使用模式。保存为无 BOM 的 UTF-8，并确保文件末尾有换行：

```proguard
# 脚本入口
-keep class com.example.plugin.api.ScriptApi { public *; }

# 运行时拼接或反射访问的目标
-keep class com.example.plugin.internal.ReflectiveTarget { *; }

# JNI 桥接与描述符类型
-keep class com.example.plugin.NativeBridge
-keepclassmembers,includedescriptorclasses class * {
    native <methods>;
}

# Java 序列化 hook 与持久化模型
-keepnames class * implements java.io.Serializable
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object readResolve();
}
-keep class com.example.plugin.model.StoredState { <fields>; }

# 运行时读取的元数据
-keepattributes Signature,RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations
```

权威准入逻辑位于 `plugin-api/r8-compiler-api/src/main/java/org/autojs/plugin/r8compiler/api/R8RulePolicy.kt`。未来协议若扩展规则表面，应以对应版本冻结的契约为准。
