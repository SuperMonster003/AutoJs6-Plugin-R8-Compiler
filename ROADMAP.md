# AutoJs6-Plugin-R8-Compiler 开发路线图

更新日期: 2026-08-27

本路线图按 "阶段 (Gate)" 推进: 每个条目都应当可勾选, 可落地, 并给出可核验的完成定义.
G1 至 G8 为已完成的历史阶段, 其详细英文证据边界见 `docs/` 目录与 Git 历史
(旧版 ROADMAP 全文保留在提交 `277ce8a` 之前的历史中); 已冻结的 Gate 报告不会被重写,
后续能力只通过新的 Gate 增量闭环.

******

## 已完成阶段 (G1 ~ G8)

### G1: 独立契约 AAR (2026-08-14)

- [x] 冻结独立命名空间, 发现 action, 引擎标识, wire 格式, AIDL 描述符与 Java 可见 JVM ABI.
- [x] 校验有界规范化输入/产物包, 有序身份与 SHA-256 绑定; 危险规则指令 fail-closed.
- [x] 以 append-only 方式发布不可变的本地 `0.1.0` AAR 分发 (protocol-wire-api 与 r8-compiler-api), 并证明脱离源码工程的独立消费者可以编译通过.

### G2: 提供者实现 (2026-08-24)

- [x] 添加独立标识的应用与隔离的 `R8CompilerService` (进程 `:r8`, 权限 `org.autojs.permission.PLUGIN`).
- [x] 实现有界 R8 执行 (固定 8.13.17, FULL_RELEASE, 无 D8/dx 路径) 与五产物原子发布.
- [x] JVM 层验证 Binder/PFD 所有权, 取消, 超时, 恶意输入与工作区恢复 (6 套件 / 42 测试).

### G3: 宿主集成 (2026-08-25)

- [x] 宿主 (AutoJs6) 添加默认关闭的开发者选项精确组件选择器, 绑定签名与协议 1.0 协商.
- [x] 落地 `runtime.loadJarWithR8(...)` 三重载 (keep 规则必填; 可选有序 classpath 与 consumer 规则); `runtime.loadJar(...)` 等原有入口不受影响.
- [x] 证明调度后任何失败都保持 R8 终态, 绝不回退 D8/dx; R8 缓存域 (`autojs6:r8-compiler:v1`) 与 DEX 语义缓存完全隔离.

### G4: 兼容性语料 (2026-08-24)

- [x] 26 格 Java/Kotlin x minApi 24-36 真实 R8 语料: 反射, 动态类名, JNI 描述符, 序列化, 脚本 API 与移除诱饵逐项验证; 宿主 8 项 Rhino/runtime 路由测试同步通过.

### G5: 本地签名发布 (2026-08-25)

- [x] 建立 append-only 本地发布目录 (`releases/provider/0.1.0-provider-dev/local.N/`), 双离线快照可复现构建, 独立签名字节级一致 (v2/v3, 单一授权证书).
- [x] `local.1` 至 `local.4` 逐代次修复: PowerShell 发布环境依赖, API 24-28 core-library desugaring, Sony procfs 限制 (改用零字节 `Os.read`/`Os.write` 探针).

### G6: 设备验收 (2026-08-25)

- [x] 授权跨 APK Binder/PFD 设备验收: 真机 (API 28 arm64) 与两台 AVD (API 25/28), 快乐路径 + 生命周期 + 进程死亡共 9/9 用例, 9 份结构化回执全部验证.

### G7: ART, JNI 与 Retrace 闭环 (2026-08-25)

- [x] 以字节校验的内置 Android 36 平台库替换设备 boot classpath 作为编译库, 发布 append-only `local.5` 代次.
- [x] 在 API 25/28/37 ART (含 16 KiB 页大小 AVD) 上执行优化产物, 验证 arm64/x86/x86_64 JNI 真实调用.
- [x] 校验 mapping 哈希后, 用字节固定的 R8 8.13.17 Retrace 从混淆堆栈还原原始类, 方法与源码行.

### G8: 隐私规范化的私有远程发布 (2026-08-25)

- [x] 首次推送前将全部提交作者/提交者身份规范化为 GitHub noreply 身份 (消息, 日期, 拓扑与树保持不变).
- [x] 创建并验证 Private 仓库后推送规范化历史与注解标签 `v0.1.0-provider-dev-private.1`.
- [x] 以非草稿 Private prerelease 发布 `local.5` 的 5 项资产 (签名 APK, 两个契约 AAR, 清单, SHA256SUMS), 全部重新下载并逐字节校验; `publicPublished` 保持 `false`.

### 历史证据锚点

| Gate | 最终 invocation | Gate 报告 SHA-256 |
|---|---|---|
| G2 v2 | `e8d210c8-0022-47be-9846-4a17b11c6e52` | `c7913af510bc24ab2a24c989886722f351e4d72f307f7fc0bff4aba6e296bfae` |
| G3 v4 | `49f8c6cb-e547-4fea-9bc1-c53be48fff75` | `b4c8e257e31c151fd069cac26a3d33470ec8f4dfacc6d7760b163b542f57b53a` |
| G4 v1 | `0c7b701f-918d-4b45-9da4-e731b27cfe63` | `879d4682bdf485e71bd883308d5a59eebaeb393b93973f131cbd14cbdf9214f1` |
| G5 v1 | `a85ba7ca-0177-4575-8f20-c6d35d58e1ea` | `994a9ba471e94aef78423a4bb313dea2be84a4a77c7b731665e1cfd28ab0826f` |
| G6 v1 | `1d978a79-4d08-41d8-a443-0115fb91cb59` | `24fc3e2b09182859e4405ab1d125efd2fefdced843f0f89bc106c70e60e32970` |
| G7 发布 | `2efce169-b337-47e5-8814-b2aa152232a4` | `fe3df1fdce2b6ff675b41cad8d2da4720a6554da86230f11d1440f1d5f66953d` |
| G7 运行时 | `36e7e2ff-b734-4034-96ab-cce5a0a037f5` | `263a80a840b93d73de31e727ce9a76a824e44f326f3ae99b22a6f64850a466ff` |
| G8 v1 | `ab010b7d-800f-43d9-acc9-27efb087efa2` | `ead4d551ae7eb13e319bc5ffed3639edc1ab96c6a85b9088ed7ca070f0a3e000` |

G1 的不可变报告为 `CONTRACT_AAR_ONLY` (隐私规范化后对应提交 `2ce4d296a69fc78ff373a39630a1b3796bae9fe7`);
两个契约 AAR 的 SHA-256 分别为 `1d97a5b44b2c20e85aa12b263fca604a32d6d89275d47a19076861cd20c29a36` (protocol-wire-api)
与 `e9df49b7e49992615a15bc0af2372a4525f02b4a2a915a560ddab3128bb2f066` (r8-compiler-api).
各阶段完整边界描述见 `docs/contract-boundary.md`, `docs/r8-compatibility-corpus-v1.md`,
`docs/local-release-v1.md`, `docs/device-acceptance-v1.md`, `docs/art-jni-retrace-acceptance-v1.md`
与 `docs/private-remote-release-v1.md`.

******

## G9: 公开发布

目标: 将当前 Private 仓库与 prerelease 转为可公开获取的正式发布. G8 明确约定可见性转换是独立的未来 Gate, 转换前必须完成完整复审.

- [ ] 公开前复审: 重新审计完整 Git 对象库, 默认分支, 全部标签, Release 资产, Actions 历史/日志, 跟踪路径与密钥/隐私扫描结果; 复审报告存入 `docs/` 并绑定被审计的提交与资产哈希.
- [ ] 仓库可见性 Private 到 Public 切换, 切换后独立验证 API 返回的可见性状态, 并同步更新 `docs/identity-reservation.json` 中 `publication` 与 `claims` 字段.
- [ ] 发布首个公开版本 (非 prerelease): 包含签名 APK, 两个契约 AAR, 发布清单与 SHA256SUMS; 发布后从公网独立重新下载并校验全部资产字节与哈希.
- [ ] 确认包含 `runtime.loadJarWithR8()` 集成的 AutoJs6 公开版本可用, 并在 README (`paired_host_build`) 中记录经验证的最低宿主 build.
- [ ] README 头部追加 GitHub 徽章 (Release / Issues / License), 并在 AutoJs6-Official-Plugins-Index 注册本插件条目.

## G10: Retrace 能力开放

目标: 把当前仅作溯源的 `RETRACE_METADATA` 与缓存 mapping 变成用户可用的堆栈还原能力. 协议 1.0 有意不含 retrace RPC, 本阶段以协议 1.1 增量扩展实现.

- [ ] 契约设计: 在 `docs/` 起草协议 1.1 retrace 契约 (请求/响应 wire 格式, mapping 溯源绑定, 预算上限与错误码), 保持无路径与 fail-closed 语义; 契约通过评审后按 G1 同等标准冻结.
- [ ] 提供者实现 retrace RPC: 接收混淆堆栈文本与 mapping 标识, 校验 mapping 哈希后调用内置 R8 retrace 还原, 输出还原堆栈; 新增对应 JVM 测试套件.
- [ ] 宿主脚本入口 (例如 `runtime.retraceR8Stack(...)`): 未选择提供者时 fail-closed; 联动文档站, TypeScript 声明与 Offline Docs 同步.
- [ ] 产物导出: 为 `loadJarWithR8` 提供可选的 mapping/seeds/usage 导出能力 (导出目录参数或专用 API), 落盘前重新哈希校验, 不破坏现有缓存语义.
- [ ] 端到端验证: 复用 G7 的堆栈样本与设备矩阵 (API 25/28/37), 通过脚本入口完成一次真实混淆崩溃的还原并留存回执.

## G11: 文档与用户体验

目标: 让普通用户无需阅读协议文档即可理解, 安装与使用插件.

- [x] 参照 DEX Compiler 插件建立 Python 多语言文档管线: `.readme/` 与 `.changelog/` JSON 源 + `.python/generate_markdown.py`, 生成 10 种语言的 README 与 CHANGELOG (含仓库根目录 README.md / CHANGELOG.md). (2026-08-27)
- [x] 重写面向用户的 README (简介, 工作原理, 与 DEX 插件的关系, 安装指南, FAQ, 能力边界, 技术参考) 与按发布代次组织的 CHANGELOG. (2026-08-27)
- [ ] 文档一致性自检: 提供检查脚本 (或后续 CI 任务), 重新生成文档并与工作区内容对比, 不一致即失败, 防止手改生成物.
- [ ] keep 规则入门指南: 面向脚本作者的常见配方 (Packages 反射访问, JNI 方法, 序列化类, 保留公共 API 面), 存入 `docs/` 并从 README 链接.
- [ ] 应用内说明: 为插件补充最小信息展示 (版本, 服务组件状态, 更新日志入口) 与 10 种语言的界面字符串; 在此之前至少保证发布页描述与 README 同步.

## G12: 引擎升级与兼容性维护

目标: 在不破坏已冻结契约与缓存语义的前提下, 持续跟进编译器与 Android 平台演进.

- [ ] R8 升级 Gate: 评估并升级固定 R8 版本 (当前 8.13.17); 升级必须重跑全部 JVM 套件与 26 格兼容语料, 产出新的 Gate 报告与新的发布代次后方可发布.
- [ ] 平台演进评估: 跟踪协议 capability 的 minApi/编译库上限 (当前 24-36 与 Android 36 平台库), 评估 API 37+ 支持与新版内置平台库的字节固定方案.
- [ ] 性能基准: 参照 DEX 插件 R5.2 基准方案, 建立大/中/小语料的冷/热编译耗时与峰值内存基线, 明确数字化晋级门槛并记录在 `docs/`.
- [ ] 并行度与内存调优: 在基准之上评估 R8 工作线程与内存上限配置, 要求输出产物与缓存语义保持不变.
- [ ] 依赖 pin 巡检: 定期核对 `desugar_jdk_libs_nio` (当前 2.1.5) 与 Android 平台库 pin 的可用更新; 任何升级都需重跑 API 24-28 低版本设备验收.

******

## 维护原则

- `releases/` 目录保持 append-only: 任何代次只能新增, 不得覆盖或删除; 已冻结的 Gate 报告与历史证据不重写.
- 协议兼容性: 1.x 内只做增量扩展 (如 G10 的 retrace); 破坏性变更需要新的主版本与新的冻结契约分发.
- 文档唯一可编辑源为 `.readme/` 与 `.changelog/` 下的 JSON 与模板; 生成的 Markdown 不手改.
- 每个新 Gate 沿用既有模式: 失效优先 (fail-closed), 报告绑定 invocation 与 SHA-256, 先负向验证再正向闭环.
