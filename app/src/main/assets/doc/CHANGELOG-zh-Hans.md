******

### 版本历史

******

# v0.2.2

###### 2026/09/19

* `修复` AGP 9.1 构建时的 SDK XML v4 解析警告, 以及 JVM 单元测试误触发 APK 原生库对齐检查的问题 (共享构建插件 1.8.3)
* `优化` compileSdk/targetSdk 升级至 37 (Android 17)

# v0.2.1

###### 2026/09/13

* `优化` 统一多语言资源, 明确插件激活契约并校验发布产物

# v0.2.0

###### 2026/09/13

* `优化` 构建阶段阻止意外引入原生依赖, 并输出 JSON 校验报告
* `优化` 统一多语言资源, 明确插件激活契约并校验发布产物

# v0.1.0-provider-dev-private.1 (local.5)

###### 2026/08/25

* `提示` 私有预发布版本, 包含签名 APK, 契约 AAR 及校验清单, 尚未公开发布
* `提示` 安装后默认不生效, 需在 AutoJs6 开发者选项中手动启用; 详细步骤见 README 的 "安装与使用" 章节
* `新增` 内置 Android 36 平台编译库, 避免部分设备的 boot JAR 缺少实际类内容而编译失败
* `新增` R8 诊断信息, 覆盖插件启动及引擎导入失败, 隐去路径并限制输出大小
* `优化` 补充 Android 7.1/9/17 及 16 KB 内存页环境的产物验证, 覆盖反射, 序列化, JNI 和 Retrace 调用栈还原

# v0.1.0-provider-dev (local.4)

###### 2026/08/25

* `修复` 部分设备限制访问 procfs 而无法校验文件描述符的问题, 改用 Os.read/Os.write 探测
* `优化` 补充 Android 7.1/9 的跨进程设备测试, 覆盖正常调用, 生命周期, 非法输入及进程退出

# v0.1.0-provider-dev (local.3)

###### 2026/08/25

* `修复` Android 7 至 9 缺少 Java 11 核心库接口而运行失败的问题, 使用 desugar_jdk_libs_nio 2.1.5

# v0.1.0-provider-dev (local.2)

###### 2026/08/25

* `修复` 发布流程受 PowerShell 模块自动加载影响而无法稳定复现签名产物的问题

# v0.1.0-provider-dev (local.1)

###### 2026/08/25

* `提示` 首个本地签名版本, 支持离线可复现构建及仅追加的本地发布归档
* `新增` runtime.loadJarWithR8 编译接口, 支持代码裁剪, 优化及混淆
* `新增` DEX ZIP, mapping, seeds, usage 及 retrace 元数据输出, 各产物提供 SHA-256 校验
* `新增` 独立 R8 编译缓存, 编译失败时返回错误而不自动切换至 D8/dx
* `新增` 独立进程编译, 仅接受同签名宿主调用, 无需网络及存储权限
* `新增` 输入归档, UTF-8 规则, 类数据及输出大小校验, 拒绝非法或超限请求
* `新增` minApi 24 至 36 编译支持, 覆盖 Java/Kotlin, 反射, JNI 及序列化
* `新增` 纯 JVM 实现, 单个 universal APK 覆盖所有设备架构
* `依赖` 附加 Google R8 版本 8.13.17 (com.android.tools:r8)

# v0.1.0 (contract)

###### 2026/08/14

* `提示` 协议契约冻结, 不含应用与运行时行为; 本条目记录接口边界的建立
* `新增` 冻结独立 R8 编译协议 1.0: API 命名空间 org.autojs.plugin.r8compiler.api, 发现 action org.autojs.plugin.R8_COMPILER, 引擎标识 `r8-compiler`
* `新增` R8 流式输入及产物格式, 提供 AIDL 接口及 protocol-wire-api/r8-compiler-api 0.1.0 契约包
