# Legado Compose 渐进式迁移方案（Android）

> 状态：草案 v1　|　**当前范围：Android 端 Compose 迁移（KMP / 跨平台已暂缓）**
> 适用仓库：`sweetpoints/legado`（上游 `gedoor/legado`），`master` 分支
> 本文所有规模数据均为在本仓库实测所得，非估算。

> ⚠️ **范围说明**
> 本文档的主线是**在 Android 上把 View 页面渐进式迁到 Jetpack Compose**。
> 团队已决定"先不弄 KMP"，因此第 2、3、4、7、8 节（跨平台选型、KMP/CMP/Flutter 调研、
> 书源兼容性扫描、Spike 定义、跨平台路线图）**属于存档资料，不是当前行动计划**——
> 保留是因为其中的实测数据（尤其是 4.9 节对 3310 条真实书源的扫描）结论不会过期，
> 将来若重启跨平台可直接复用。
>
> **当前该看的章节**：第 1 节（基线）、第 5 节（已落地的 Compose 接入）、
> 第 6 节（互操作手册）、第 9 节（工作量）。下一步是继续第 5.5 节的 Phase 2 批次。

---

## 0. 结论摘要

1. **Compose 接入已完成并验证**：Phase 0 脚手架（编译链、依赖、`BaseComposeActivity`、主题桥）+ About 页完整迁移，`:app:compileAppDebugKotlin`、`:app:testAppDebugUnitTest`、`:app:assembleAppDebug`、`:app:assembleAppRelease` 全部通过。详见第 5 节。
2. **渐进式迁移是低风险、可随时回退的**：老 View 页面完全不受影响，新页面可以纯 Compose，两者在同一界面内共存。
3. **为解锁 Compose 1.12.x 同步完成了 AGP 大版本升级**（AGP 8.13.2 → 9.1.1、Gradle 8.14.4 → 9.3.1、compileSdk 36 → 37）。这是独立的一组改动，可单独回退，详见 5.9 节。
4. **升级中发现并修复了一处 R8 回归**（AGP 9 把「缺失类」从警告升为错误），详见 5.12 节。
5. **发现一个既有的 debug 打包阻塞点（已解决）**：`:app:assembleAppDebug` 在**未改动的 HEAD 上也会失败**——第三方 Rhino fork 用了 `MethodHandle`/`VarHandle`（要求 minSdk ≥ 26，项目原为 23）。已按决策把 **minSdk 提升到 26**，`assembleAppDebug` 现已通过，详见 5.11 节。
6. **About 页已完成全量 Compose 化**（偏好列表也用 Compose 重写，`AboutFragment` 与 `R.xml.about` 已删除），可作为 `ui/config` 等设置类页面的样板，详见 5.4 节。
7. **下一步**：按 5.5 节的批次划分继续迁移，推荐先做 **P2-2（`ui/config`）**——About 页已把设置类页面的样板打好；P2-1 经代码实况核查后大部分不适合迁移，详见 5.5。
8. **⚠️ 尚未验证**：真机运行、书源回归、排版回归均未测试。仅编译、单元测试与打包通过。

---

## 1. 现状基线（实测）

### 1.1 代码规模

| 指标 | 数值 |
| --- | --- |
| Kotlin 总行数 | 255,817（app 主模块 190,041） |
| Kotlin 文件数 | 1,528 |
| Activity 类 | 68 |
| Fragment | 124 |
| DialogFragment | 89 |
| Adapter | 104 |
| ViewModel | 103 |
| XML layout | 257 |
| 自定义 View（继承 View/ViewGroup 等） | 13（不含 `ui/widget` 内大量自绘控件） |

### 1.2 技术栈

- **UI**：纯 Android View 体系。`buildFeatures { viewBinding = true }`，无任何 Compose 引用（全仓库 `androidx.compose` 命中 0 处）。
- **基类**：`BaseActivity<VB : ViewBinding>`（[BaseActivity.kt](../app/src/main/java/io/legado/app/base/BaseActivity.kt)）统一处理主题、系统栏、背景图、多窗口、预测性返回、溢出菜单。
- **状态层**：83 个文件用 `Flow`，42 个用 `LiveData`，62 个用 `LiveEventBus`。
- **DI**：**没有** Hilt/Koin，依赖 `App.kt` 单例与 `AppConfig`。这一点让迁移比典型项目更轻——没有 DI 图需要重接。
- **minSdk 23 / targetSdk 36 / compileSdk 36**，Kotlin 2.4.10，AGP 8.13.2，Gradle 8.14.4，Java toolchain 21。
- **JVM-only 关键依赖**：

| 能力 | 依赖 | 位置 |
| --- | --- | --- |
| 规则解析 | jsoup 1.23.2、JsoupXpath 2.5.3、json-path 3.0.0 | [app/build.gradle](../app/build.gradle) |
| JS 书源 | Rhino（htmlunit fork `org.htmlunit:htmlunit-core-js:5.3.0-legado.4`） | `modules:rhino`（包名 `com.script`） |
| 数据库 | Room 2.8.4 + KSP | `data/` |
| 中文简繁转换 | `com.github.liuyueyi.quick-chinese-transfer` 0.2.17 | `utils/ChineseUtils.kt` |
| 内置服务 | NanoHTTPD + WebSocket + MCP（ktor 3.5.2 / mcp-sdk 0.15.0） | `web/` |
| 电子书 | epublib、pdfbox-android | `modules:book`、`ui/book/read/page` |

### 1.3 Android 耦合度（决定跨平台成本的关键指标）

| 层 | 引用 `android.*` 的文件 | 总数 | 耦合比例 |
| --- | --- | --- | --- |
| `model/` | 35 | 66 | 53% |
| `data/` | 63 | 76 | 83% |

**含义**：业务层并不"干净"。`data/` 的 83% 耦合主要来自 Room 注解与 `Context`；`model/` 的 53% 耦合来自文件 IO、`Context`、TTS 等。跨平台抽取必须先处理这一层——这也正是为什么"直接上 Flutter/CMP 写 UI"没有意义。

### 1.4 阅读器：自绘排版引擎（本项目真正的技术资产）

`ui/book/read/page/` 下的核心文件：

| 文件 | 行数 | 职责 |
| --- | --- | --- |
| [ReadView.kt](../app/src/main/java/io/legado/app/ui/book/read/page/ReadView.kt) | 1,222 | 阅读主视图、翻页调度 |
| [ContentTextView.kt](../app/src/main/java/io/legado/app/ui/book/read/page/ContentTextView.kt) | 1,145 | 继承 `View`，`onDraw(canvas)` 手绘正文 |
| [PageView.kt](../app/src/main/java/io/legado/app/ui/book/read/page/PageView.kt) | 581 | 页面容器 |
| [HighlightDraw.kt](../app/src/main/java/io/legado/app/ui/book/read/page/HighlightDraw.kt) | 317 | 高亮绘制 |
| `ZhLayout.kt` / `TextMeasure.kt` / `TextLine.kt` / `TextPage.kt` / `TextChapterLayout.kt` | — | 自研中文分页断行与测量 |

关键事实：**连 `StaticLayout` 都没有使用**，断行与分页完全自研。这一层是任何跨平台方案的最大成本项。

---

## 2. （存档）选型：Compose / KMP+CMP / Flutter

> 🗄️ **KMP 已暂缓**。本节保留用于将来重启跨平台时复用，不是当前行动计划。

### 2.1 决策表

| 维度 | Jetpack Compose（仅 Android） | KMP + Compose Multiplatform | Flutter |
| --- | --- | --- | --- |
| 性质 | 渐进式重构 | 渐进式重构 + 核心抽取 | 从零重写 |
| 复用现有 Kotlin 业务层 | ~100% | 高（需先解耦） | 0 |
| 复用 JVM 库（jsoup/Rhino/HanLP） | 完全 | Android 侧完全；iOS 侧**需替代品** | 无 |
| 阅读器自绘引擎 | `AndroidView` 包一层，零改动 | Android 侧保留；iOS 侧需重写 | 必须重写，且无等价 API |
| 书源 JS 兼容性 | 不变 | Android 不变；iOS **高风险** | 高风险 |
| iOS 产物 | 无 | 有 | 有 |
| 风险 | 低，可灰度可回退 | 中高，取决于两个 Spike | 极高，一次性赌博 |

### 2.2 为什么 Flutter 在这里不成立

Flutter 侧要重建的东西，逐项都对应项目的核心能力：

| Legado 能力 | Flutter 侧现状 |
| --- | --- |
| jsoup + JsoupXpath 的规则语义 | Dart 无等价物，需重写规则引擎 |
| Rhino 执行 JS 书源 | Dart 无 Rhino 等价物；`dart:js` 是浏览器 JS，Flutter 需 QuickJS 系绑定，语义与 Rhino 不同 |
| Room schema + DAO | 需整体重写 |
| 中文简繁转换（`quick-chinese-transfer`） | Dart 无等价物（可换 OpenCC，但仍是重写绑定） |
| NanoHTTPD + WebSocket + MCP server | 需重写（Ktor 有 Dart 侧替代，但 MCP SDK 是 Kotlin） |
| epublib / pdfbox | 需重写 |

**结论**：如果目标是"把现有产品搬到 iOS"，Flutter 意味着重写解析引擎、规则引擎、数据层和排版引擎——这已经是一个新产品，而不是迁移。仅在"Android 端降级为解析后端 + Flutter 只做壳"的架构下才成立，但那会引入进程间通信、性能与离线体验问题。

### 2.3 推荐路线

**推荐：KMP 优先，UI 走 Compose Multiplatform；但先做两个 Spike 再决策。**

理由：代码库 100% Kotlin，业务层是有形资产，KMP 是唯一能复用它的跨平台路径。Flutter 会把这份资产全部作废。经核实（第 4 节），**UI 层已不再是风险点**——CMP 的 iOS 已于 2025-05 转 Stable，且自绘排版所需的 `TextMeasurer`/`TextLayoutResult`/`drawText` 都是 Common API。

**但必须诚实指出，真正的风险已经转移**：

1. **JsoupXpath 无法移植**（第 4.4 节）——书源里的 XPath 规则在 iOS 上需要改写，直接影响存量书源兼容性；
2. **Rhino 无 iOS 方案，且障碍是宿主对象模型**（第 4.5 节）——`Packages.*`/`JavaImporter` 类书源必然不兼容；
3. **版本矩阵有一个零余量的冲突**（第 4.2 节）——Kotlin 2.4.10 与 AGP 9.1.0 正好卡在一起。

这三点使 **Spike-1（书源规则在 iOS 的兼容率）成为整条路线的 go/no-go 判据**，而不是 UI 问题。详见第 7 节。

---

## 3. （存档）跨平台阻塞点清单（按**实测影响面**降序）

> 🗄️ **KMP 已暂缓**。这些阻塞点只对跨平台路线成立；**在纯 Android 路线上它们都不存在**
> （Android 上 jsoup / JsoupXpath / Rhino / 自绘排版全部照旧工作）。
> 仅当将来重启跨平台时，本节才是行动清单。

> "实测影响面"一列来自 **3310 条真实书源**的扫描结果（4.9 节），不是估计。

| # | 阻塞点 | 实测影响面 | 可选处理路径 | 验证方式 |
| --- | --- | --- | --- | --- |
| B1 | **JsoupXpath 无 KMP 移植**（依赖 jsoup 具体实现 + ANTLR4 + Java 运行时，工程上不可移植） | **40.2%** 书源 / 24,069 个片段 | ① 把 XPath 规则改写为 CSS（**影响存量书源**）；② 自研 XPath 1.0 子集；③ iOS 端不支持 XPath 书源 | **Spike-1（新的头号判据）** |
| B2 | **Rhino 无 iOS 实现**；但障碍主要在**宿主绑定层**而非语法——`Packages.*`/`JavaImporter` 无对应物 | 硬互操作仅 **1.2%**（41 个书源）；需重建的宿主 API **51 个方法**；**26.3%** 书源需 JS 引擎 | ① 换 QuickJS 系引擎 + 重建宿主层（51 个方法）；② iOS 端不做 JS 书源；③ 远程执行 JS | Spike-1b |
| B3 | **自绘中文排版引擎**（自研 `ZhLayout`/`TextMeasure`，未用 `StaticLayout`） | 影响阅读体验本身；**但不否决 CMP**（该引擎本来就是 Android 独占 View 代码，iOS 无论如何要新写） | ① 用 Compose `TextMeasurer`/`Paragraph` 重写（API 已核实够用）；② iOS 单独实现 | Spike-2 |
| B4 | **`data/` 83% 引用 `android.*`** | 全部书源（基础设施层） | 逐文件去除 `Context` 依赖，改为注入 | 编译 `commonMain` |
| B5 | **Room 需 bundled SQLite driver**（Room 2.7.0+ 已是 KMP 库，有官方路径） | 全部书源（基础设施层） | 升级 Room 并改造 driver 注入 | 官方文档 |
| B6 | **中文处理库无 KMP 产物**（实际用 `quick-chinese-transfer`；**不是** HanLP——README 致谢里的 `com.hankcs:hanlp` 是过时条目，全仓库命中 0） | `java.t2s` 被调用 57 次 | 用 **OpenCC**（C++，可 cinterop/XCFramework）替代 | 最容易 |
| B7 | **NanoHTTPD / MCP server 是 JVM-only** | 与书源无关，仅影响 Web 服务功能 | ① 已有 ktor 依赖，改为 Ktor server；② iOS 端不提供 | 依赖替换 |
| B8 | **257 个 XML layout + 104 个 Adapter** | UI 改写量大但机械 | Compose 渐进式迁移（第 5 节） | 编译 + 回归 |

---

## 4. （存档）跨平台技术现状核实

> 🗄️ **KMP 已暂缓**。本节是外部一手来源的调研结论，供将来重启时复用。
> 其中 **4.9 节（3310 条真实书源的兼容性扫描）是本文档最有价值的实测数据**，结论不会过期。

> 结论均取自官方一手来源（kotlinlang.org / developer.android.com / blog.jetbrains.com / Maven 元数据 / GitHub 仓库）。**【核实】**=取自官方来源，**【推断】**=基于已核实事实的判断。

### 4.1 Compose Multiplatform 的 iOS 支持

**结论：iOS 自 2025-05（CMP 1.8.0）起为 Stable、官方称生产可用；剩余短板集中在文本输入 IME 与高对比度无障碍。UI 层已不再是主要风险。**

- 官方平台稳定性表：CMP UI 框架 **iOS / Android / Desktop = Stable**，Web(Kotlin/Wasm) = Beta。【核实】[Stability of supported platforms](https://kotlinlang.org/docs/multiplatform/supported-platforms.html)
- 稳定性里程碑原文："Compose for iOS to **Stable** … All major APIs are now officially stable"；官方给出启动≈原生、滚动≈SwiftUI、iOS 包体仅 +~9 MB。【核实】[Compose Multiplatform 1.8.0 发布公告](https://blog.jetbrains.com/kotlin/2025/05/compose-multiplatform-1-8-0-released-compose-multiplatform-for-ios-is-stable-and-production-ready/)
- 最新稳定版 **1.12.1**；最低 iOS 14、仅 64 位；"最新 CMP 永远兼容最新 Kotlin"。【核实】[Compatibility and versions](https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html)
- 无障碍：语义树已映射到 iOS Accessibility（VoiceOver / AssistiveTouch / Full Keyboard Access 均支持），但**高对比度需自行实现**（Material3 在 CMP 侧无开箱支持）。【核实】[iOS accessibility](https://kotlinlang.org/docs/multiplatform/compose-ios-accessibility.html)
- 已知短板：官方**没有**汇总的"iOS 限制清单"。社区 issue 层面有 readOnly 键盘抖动 [#5088](https://github.com/JetBrains/compose-multiplatform/issues/5088)、TextField 无 past/select [#5259](https://github.com/JetBrains/compose-multiplatform/issues/5259)。另外 Material3 在 CMP 侧坐标仍是 alpha（1.12.0-alpha03）。

### 4.2 KMP 与版本兼容性 —— ⚠️ 这里有一个必须提前处理的冲突

**结论：KMP 的 iOS/Android/Desktop/Server 均 Stable；但本仓库当前版本组合与 Compose 1.12.x 的要求相互冲突。**

官方兼容矩阵（【核实】[KMP compatibility guide](https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html)）：

| KMP 插件 | Gradle | AGP | Xcode |
| --- | --- | --- | --- |
| 2.4.20 | 7.6.3–9.7.0 | 8.5.2–**9.3.1** | 26.4 |
| 2.4.0–2.4.10（= 本仓库） | 7.6.3–9.5.0 | 8.5.2–**9.1.0** | 26.4 |

⚠️ **冲突**：Compose 1.12.x 要求 **AGP ≥ 9.1.0**，而 Kotlin **2.4.10** 官方支持的 AGP 上限正好是 **9.1.0**（零余量）。本次为解锁 Compose 1.12.x 把 AGP 升到 9.1.1，已略微越过 2.4.10 的官方矩阵。**一旦真正引入 KMP，应把 Kotlin 一并升到 2.4.20**（支持 AGP 至 9.3.1、Gradle 至 9.7.0），届时 AGP 可继续上探。

KMP 官方还明确劝退"同一 Gradle 工程声明多个相似 target"，建议按 module 拆分——这与第 8 节的 `core` / `platform` 模块化思路一致。

### 4.3 Room 的 KMP 支持

**结论：Room 自 2.7.0 起即为 KMP 库并支持 iOS，官方有明确路径；B5 风险降级为"改造量"而非"可行性"。**

- 2.7.0 release notes 原文："Room has been refactored to become a Kotlin Multiplatform (KMP) library. Current supported platforms are Android, iOS, JVM (Desktop), native Mac and native Linux."【核实】[Room release notes](https://developer.android.com/jetpack/androidx/releases/room)
- 官方 KMP 配置文档：需要 `androidx.sqlite:sqlite-bundled`（稳定 2.7.1）+ `androidx.room3:room3-runtime`；iOS 需 `kspIosArm64`/`kspIosX64`/`kspIosSimulatorArm64`；原文建议 "**bundling** it with your app to prevent inconsistencies between the platform implementations of SQLite"。【核实】[Set up Room database for KMP](https://developer.android.com/kotlin/multiplatform/room)
- Room 3.0（2026-03）：迁到 `androidx.room3` 包、强制 KSP、DAO 必须 suspend 或返回 Flow、新增 JS/Wasm 支持。【核实】[Room 3.0 公告](https://android-developers.googleblog.com/2026/03/room-30-modernizing-room.html)

> 本仓库当前用 Room 2.8.4 + `room` Gradle 插件。上 KMP 时需评估是否迁到 `androidx.room3`（包名与 API 都是破坏性变更）。

### 4.4 规则引擎：jsoup 可移植，**JsoupXpath 不可移植**

**结论：jsoup 有可用 KMP 移植 Ksoup；但 JsoupXpath 在工程上无法移植——这可能是比 Rhino 更早爆发的问题。**

- **Ksoup**（`com.fleeksoft.ksoup`）：发布 target 含 `iosArm64` / `iosSimulatorArm64` / `iosX64` / `jvm` / `androidJvm` / `js` / `linux*` / `macos*`（Gradle module metadata 实证）；最新 0.2.6（2026-02-19）。README 自述 "**Most** of the APIs work without any changes"——兼容程度官方只承诺"大多数"。【核实】[fleeksoft/ksoup](https://github.com/fleeksoft/ksoup)
  - 注意：Ksoup 按能力拆扩展（`ksoup-kotlinx` / `ksoup-okio` / `ksoup-network`(Ktor 3)），核心库只做"从字符串解析"——而本仓库依赖 OkHttp + 自定义网络层，需要额外适配。
- **JsoupXpath**（`cn.wanghaomiao`，最新 2.5.5）：纯 Java，基于 jsoup **具体实现类** + Antlr4 生成代码 + Java 运行时；**无任何 Kotlin/Native 或 KMP 产物**。【核实】[zhegexiaohuozi/JsoupXpath](https://github.com/zhegexiaohuozi/JsoupXpath)
  → 【推断】移植在工程上不可行。走 KMP 意味着书源里所有 XPath 规则需改写为 CSS 选择器或自研 XPath 子集。
- **json-path**：Maven Central 搜索 `jsonpath AND multiplatform` 命中 **0**。GitHub 有社区 KMP 实现（[JsonPathKt](https://github.com/eygraber/JsonPathKt)、[jsonpath4k](https://github.com/a-sit-plus/jsonpath4k)），但**都不是** jayway/json-path 的移植，与现有规则语义是否等价【未能核实】。

**行动项**：把"书源里 XPath 规则占比"做成一个可量化指标，与 Spike-1 一起评估。

### 4.5 iOS 上执行书源 JS —— 最硬的阻塞点

**结论：Rhino 没有 KMP/iOS 支持；换引擎的核心障碍不是 JS 语法，而是宿主对象模型。**

- Rhino 官方自述 "JavaScript written entirely in **Java**"；其 Kotlin 支持是 **JVM 专属**模块 `rhino-kotlin`（依赖 `kotlin.Metadata` 反射），**不是跨平台**。【核实】[mozilla/rhino](https://github.com/mozilla/rhino)
- 本仓库证据：`modules/rhino/build.gradle` 用 `com.android.library` + `kotlin.android` + `jvmToolchain(21)`，**无任何 KMP 插件/target 声明**。
- **真正的问题**——书源 JS 直接操作 Kotlin/Java 对象：
  - `AnalyzeRule.kt` 中 `bindings["java"] = this`、`bindings["source"] = source`、`bindings["cookie"]/["cache"]/["book"]/["chapter"]/["src"]/["page"]`；
  - 测试里直接使用 `new JavaImporter(Packages.javax.crypto.Mac, …)`、`new Packages.io.legado.app.api.ReturnData()`。
  → 【推断】`Packages.*`、`JavaImporter`、`Java.extend` 在 QuickJS / JavaScriptCore 中**没有对应物**，必须重写整套宿主绑定层；这类书源应被标记为不兼容。

候选路径成熟度（**没有一条是低风险**）：

| 方案 | 状态 | 关键问题 |
| --- | --- | --- |
| JavaScriptCore（自研 cinterop） | Apple 官方框架，iOS 上无第三方依赖；但**无维护中的 KMP 封装**（GitHub 仅有 2019 年 PoC） | 需自研绑定 |
| [quickjs-kt](https://github.com/qdsfdhvh/quickjs-kt)（`io.github.dokar3`） | 1.0.15（2026-09-03），target 含 iOS/Android/JVM/macOS/Linux/Windows；可 eval 任意 JS | 相对可用；宿主绑定仍需自建 |
| [Zipline](https://github.com/cashapp/zipline)（Cash App） | 1.28.0，最成熟（2305 star） | 为跑 **Kotlin/JS 模块**设计，且官方明确 "**It does not offer a sandbox or process-isolation and should not be used to execute untrusted code**"——对"用户自定义书源"是定位错配；另硬绑定 Kotlin 编译器版本 |
| [KiteJS](https://github.com/yuroyami/KiteJS) | 0.2.0，0 star，2026-09 新建 | 语义上最接近 Rhino（就是 Rhino 移植），但不支持 class/module/async-await，单作者，**不可用于生产** |

### 4.6 中文处理

**结论：本仓库并**不**使用 HanLP。** 这一点值得强调，因为它说明"依赖清单不能只看 README"。

- 事实核查：全仓库（`*.kt`/`*.java`/`*.gradle`/`*.toml`）搜索 `hankcs`/`hanlp` **命中 0**；`README.md` 第 29 行的 `com.hankcs:hanlp` 是**过时的致谢条目**。实际实现是 [ChineseUtils.kt](../app/src/main/java/io/legado/app/utils/ChineseUtils.kt) 调用 `com.github.liuyueyi.quick.transfer.ChineseUtils`（`s2t`/`t2s`/`preLoad`/`loadExcludeDict`），依赖版本 0.2.17。
- 该库同样是**纯 Java、无 KMP 产物**，但其能力（简繁转换）有非常成熟的跨平台替代：**OpenCC**（C++，Apache-2.0，最新 1.4.2 / 2026-08-22，10023 star），iOS 可经 cinterop 或 XCFramework 复用。【核实】[BYVoid/OpenCC](https://github.com/BYVoid/OpenCC)
- 若将来需要分词：cppjieba（C++，活跃）可用，但 iosjieba **2018 年后未再更新**，且 jieba 系与其它方案精度不完全等价。

**结论：这是三块 JVM 绑定里最容易的一块。**

### 4.7 Compose 文本 API 能否重建中文分页排版

**结论：基本够用**；但缺少 StaticLayout 那种"逐行增量重排、外部按行索引驱动"的公开低阶 API。

- `TextMeasurer` 是 **Common（多平台）** API，`measure(...)` 返回 `TextLayoutResult`，支持 `constraints`/`maxLines`/`softWrap`/`overflow`/`placeholders`/`skipCache`，内部有 LRU 缓存。【核实】[TextMeasurer](https://developer.android.com/reference/kotlin/androidx/compose/ui/text/TextMeasurer)
- 绝对定位绘制是 public API：`DrawScope.drawText(textLayoutResult, color, topLeft, alpha, …)`、`DrawScope.drawText(textMeasurer, text, topLeft, style, …)`——可把算好的结果画在任意 `topLeft`。【核实】[androidx.compose.ui.text](https://developer.android.com/reference/kotlin/androidx/compose/ui/text/package-summary)
- 命中测试：`TextLayoutResult` 提供 `getLineForVerticalPosition` / `getOffsetForPosition` / `getBoundingBox` 等双向查询，足够实现"点击某字/某行"。
- **限制**：逐行查询是**只读**的，重排必须整体重新 `measure`。若现有 `ZhLayout`/`TextMeasure` 依赖 StaticLayout 风格的增量/按行外部驱动，需改为"分块（chunk）measure + 自管缓存"。
- 【未能核实】是否存在官方"按行 lazy 重排 + 局部失效"的低阶 API。

### 4.8 Flutter 路线的事实基础

**结论：Flutter 无法复用上述任何 JVM 库；Dart 侧替代品全部是"语义不同的重写"，复用收益为零。**

- Dart 官方 Java 互操作（`package:jnigen` / `package:jni`）是 **JNI** 绑定，**iOS 无 JVM，不可用**。【核实】[Java interop using package:jnigen](https://dart.dev/interop/java-interop)
- `dart:ffi` 只能调 C ABI 原生库，无法调用 JVM 字节码/Java 类。【推断】
- Dart 侧替代品实况：
  - HTML 解析：[package:html](https://pub.dev/packages/html) 0.15.7（dart.dev 官方团队包），Dart 原生实现，与 jsoup 的 CSS/DOM 行为非逐位等价，且**无 JsoupXpath 对应物**。
  - JS 引擎：[flutter_js](https://pub.dev/packages/flutter_js) 0.8.7，自述 "uses Quickjs on Android and JavascriptCore on IOS"——**换引擎的复用收益为零**，因为宿主绑定层照样得重写。
- "Android 当后端"（本仓库已有 NanoHTTPD + MCP server）技术上可行，但会把 iOS 端变成瘦客户端，**离线阅读这一核心卖点失守**。

### 4.9 📊 实测：对 3310 条真实书源的兼容性扫描

前面各节是**技术可行性**判断，这一节是**影响面**判断——后者才能回答"值不值得做"。

数据来源（公开书源聚合库，按 `bookSourceUrl` 去重）：`aoaostar/legado` 的 4 个聚合文件（1554 + 2117 + 3907 + 939 条）与 `XIU2/Yuedu`（22 条），原始 8539 条，**去重后 3310 条**。

扫描器：[tools/source-compat-scan.py](../tools/source-compat-scan.py)｜完整报告：[docs/source-compat-report.md](source-compat-report.md)
分类逻辑**严格对齐** [AnalyzeRule.kt](../app/src/main/java/io/legado/app/model/analyzeRule/AnalyzeRule.kt) 的 `splitSourceRule` / `SourceRule.init()`（含"`/` 开头即 XPath"这条隐式规则）。

#### 规则类型分布（片段级，159,810 个片段）

| 规则类型 | 片段数 | 占比 | iOS 端影响 |
| --- | --- | --- | --- |
| CSS | 65,561 | **41.0%** | ✅ 可移植（Ksoup） |
| 模板变量 `{{page}}` | 50,779 | **31.8%** | ✅ **不需要 JS 引擎**，只是取值 |
| **XPath** | **24,069** | **15.1%** | ❌ **JsoupXpath 无法移植** |
| 模板表达式（复杂 `{{}}`） | 9,157 | 5.7% | ⚠️ 需 JS 引擎 |
| JSONPath | 5,734 | 3.6% | ⚠️ 需换实现 |
| JS（`@js` / `<js>`） | 4,501 | 2.8% | ⚠️ 需换引擎 + 重建宿主层 |
| 正则字段 | 1,256 | 0.8% | ✅ |

#### 书源级可移植性分层

| 层级 | 判定条件 | 书源数 | 占比 |
| --- | --- | --- | --- |
| **A** | 无 XPath、无硬互操作、无 JS | 1,074 | **32.4%** |
| **B** | 无 XPath、无硬互操作，但有 JS | 869 | **26.3%** |
| **C** | 含 XPath | 1,332 | **40.2%** |
| **D** | 含硬 Java 互操作 | 41 | **1.2%** |

#### 三个与直觉相反的结论

**① 真正的头号阻塞是 JsoupXpath，不是 Rhino。**
**40.2%** 的书源含 XPath 规则（24069 个片段）。JsoupXpath 依赖 jsoup 具体实现 + ANTLR4 + Java 运行时，**无法移植**——这是必须先解决的问题。相比之下 JS 相关的影响面更小。

**② "Rhino 的 Java 互操作会杀死项目"是**被高估的**。**
只有 **1.2%（41 个）** 书源使用了 `Packages.*` / `JavaImporter` / `importPackage` 这类**硬 Java 互操作**（且高度集中在 crypto 签名的书源：七猫小说、连城读书、晋江、SF轻小说、西瓜小说等）。

绝大多数 JS 用的是**宿主方法调用**，例如：

```js
java.get('url');  java.ajax(url);  java.md5Encode(x);  java.getElement(path);
```

这些**不是** Rhino 特有语法，只是调用绑定到 JS 的 Kotlin 对象——在 QuickJS/JavaScriptCore 上**可以重建**。

**③ 需要重建的宿主 API 面很窄，只有 51 个方法。**

| 方法 | 调用次数 | | 方法 | 调用次数 |
| --- | --- | --- | --- | --- |
| `java.md5Encode` | 1,016 | | `java.base64DecodeToByteArray` | 165 |
| `java.ajax` | 776 | | `java.getElements` | 73 |
| `java.put` | 773 | | `java.encodeURI` | 73 |
| `java.get` | 746 | | `java.toast` / `base64Encode` / `base64Decode` | 62 / 62 / 60 |
| `java.getString` | 570 | | `java.t2s`（简繁转换） | 57 |
| `java.log` | 320 | | `java.setContent` | 54 |
| `java.timeFormat` | 227 | | `java.aesBase64DecodeToString` | 35 |

**不同方法数合计 51 个**——这就是宿主层的完整工作量清单，是一个可估算、可排期的确定性任务，而不是"无底洞"。

#### 对路线的影响（修正结论）

| 原判断 | 实测修正 |
| --- | --- |
| "Rhino 无 iOS 实现 ⇒ 书源大面积失效" | **过度悲观**。硬互操作仅 1.2%；JS 的 95%+ 是宿主方法调用，可重建 |
| "排版引擎是最大风险" | 排版引擎**本来就是 Android 独占代码**，iOS 无论如何要新写，不构成否决项 |
| 未识别 JsoupXpath | **实为头号阻塞**：40.2% 书源受影响 |

**因此 Spike-1 的验收标准应聚焦于 XPath**：不是"能否跑 JS"，而是"**24069 个 XPath 片段里，有多少能用 CSS 重写、多少必须靠自研 XPath 子集**"。JS 与宿主层反而是更可控的部分。



---

## 5. Android 侧 Compose 渐进式迁移（已落地）

### 5.1 已完成的改动

| 文件 | 改动 |
| --- | --- |
| [gradle/libs.versions.toml](../gradle/libs.versions.toml) | 新增 `composeBom = "2026.09.00"`；新增 compose 依赖别名（bom/ui/graphics/foundation/material3/tooling/tooling-preview/runtime-livedata/ui-viewbinding）与 `lifecycle-runtime-compose`；新增插件别名 `kotlin-compose = org.jetbrains.kotlin.plugin.compose`（版本跟随 `kotlin`） |
| [app/build.gradle](../app/build.gradle) | 应用 `libs.plugins.kotlin.compose`；`buildFeatures` 增加 `compose = true`（与 `viewBinding` 并存）；新增 compose 依赖块；`testOptions.unitTests.returnDefaultValues = true`（修既有单测失败）；`minSdk 23 → 26`（见 5.11） |
| [BaseComposeActivity.kt](../app/src/main/java/io/legado/app/base/BaseComposeActivity.kt) | **新增**。Compose 页面基类 |
| [LegadoComposeTheme.kt](../app/src/main/java/io/legado/app/lib/theme/compose/LegadoComposeTheme.kt) | **新增**。旧主题体系 → Compose 的主题桥 |
| [AboutActivity.kt](../app/src/main/java/io/legado/app/ui/about/AboutActivity.kt) | 改为继承 `BaseComposeActivity`；接收从 Fragment 迁来的业务逻辑（Markdown 对话框、日志/堆转储导出） |
| [AboutScreen.kt](../app/src/main/java/io/legado/app/ui/about/AboutScreen.kt) | **新增**。About 页 Compose 实现，含纯 Compose 的偏好列表 |
| [CheckAppUpdate.kt](../app/src/main/java/io/legado/app/ui/about/CheckAppUpdate.kt) | 新增 `AppCompatActivity.checkAppUpdate` 重载（Fragment 版保留不动） |
| ~~`AboutFragment.kt`~~ / ~~`res/xml/about.xml`~~ | **已删除**（偏好列表改为 Compose，见 5.4） |
| [UpdateDialogLifecycleTest.kt](../app/src/test/java/io/legado/app/ui/about/UpdateDialogLifecycleTest.kt) | 该测试断言源码文本，更新其检查路径 `AboutFragment.kt` → `AboutActivity.kt` |
| [settings.gradle](../settings.gradle) | 移除已过时的 R8 版本钉制（见 5.9） |

> 上表只列 Compose 接入相关改动；为解锁最新 Compose 而做的 **AGP / Gradle / compileSdk 升级**是独立的一组改动，见 **5.9 节**，可单独回退。

### 5.2 设计要点：为什么没有复制 `BaseActivity`

`BaseActivity` 里有近 200 行主题/系统栏/背景图/预测性返回逻辑，直接为 Compose 写一个新的 `ComponentActivity` 基类会导致两份实现长期漂移。

采用的方案是：让 Compose 页面的根视图就是一个 `ComposeView`，再把它适配成 `ViewBinding`：

```kotlin
class ComposeRootBinding(private val composeView: ComposeView) : ViewBinding {
    override fun getRoot(): View = composeView
}
```

于是 `BaseComposeActivity` 可以直接继承 `BaseActivity<ComposeRootBinding>`，**零重复地**复用全部既有能力，同时新页面可以写纯 Compose、不需要任何 XML。

### 5.3 主题桥

旧体系取色是 `Context.primaryColor` 这类扩展属性（[MaterialValueHelper.kt](../app/src/main/java/io/legado/app/lib/theme/MaterialValueHelper.kt)），Compose 若用 Material3 默认配色会出现两套皮肤。`LegadoComposeTheme` 把旧取值收敛为 `LegadoColors` 并同时提供给 `MaterialTheme` 与 `LocalLegadoColors`：

- 缓存策略：以 `context` 为 key 做 `remember`。主题色变更时 [ThemeConfig.kt](../app/src/main/java/io/legado/app/help/config/ThemeConfig.kt) 会 post `EventBus.RECREATE` 让 Activity `recreate()`，因此无需额外订阅。
- 注意 `isDarkTheme` 由**主色亮度**决定，不是系统深色模式——迁移时不要用 `isSystemInDarkTheme()` 替代。

### 5.4 About 页：已完成全量 Compose 化（推荐作为设置类页面的样板）

About 页经历了两个阶段，现在的形态是**纯 Compose、无 Fragment、无偏好 XML**：

| 阶段 | 形态 |
| --- | --- |
| 试点（第一轮） | Compose 外壳 + `AndroidView` 承载 `AboutFragment`（View 孤岛），用于验证互操作 |
| **完成（当前）** | 偏好列表也用 Compose 重写，`AboutFragment` 与 `R.xml.about` **已删除** |

**为什么值得作为样板**：`PreferenceFragmentCompat` 没有 Compose 等价物，`ui/config`（17 个文件）等设置类页面都面临同样的问题。About 页给出了完整答案：

1. **列表声明化**：把偏好项抽成 `AboutItem(key, titleRes, summaryRes)` 的数据列表，用 `LazyColumn` 渲染，`key` 同时用于点击分发——比 `R.xml.about` + `when(preference.key)` 更集中。
2. **业务逻辑上移到 Activity**：原先散在 Fragment 里的 `showMdFile` / `saveLog` / `createHeapDump` / `copyLogs` / `copyHeapDump` / `dumpLogcat` 全部迁入 `AboutActivity`。
3. **补一个 Activity 版的工具函数**：`checkAppUpdate` 原本只有 `Fragment` 扩展，新增了 `AppCompatActivity` 重载（两者只差状态保存判断：Fragment 用 `isAdded && !childFragmentManager.isStateSaved`，Activity 用 `!supportFragmentManager.isStateSaved`）。
4. **视觉对齐**：行的内边距/字号严格对照 `view_preference.xml`（左右 16dp、上下 10dp、最小高度 60dp、标题 16sp、摘要 14sp 且上边距 8dp）；分类标题对照 `view_preference_category.xml`（上 16dp / 下 8dp / 左 16dp）。

**已知遗留**：

- ~~`activity_about.xml`、`R.menu.about`、`ids.xml` 里的 `compose_fragment_container`~~ → **已随这次清理删除**（迁移孤儿出来的死资源）。
- 分类标题原用静态 `@color/accent`，现改用主题强调色（`ThemeStore`）以跟随用户自定义主题——默认值相同，仅在用户改过强调色时有差异。
- 顶栏用 Material3 `TopAppBar`，与旧 `TitleBar` 有细微视觉差异（用户已确认接受）。
- 分隔线：原 `PreferenceFragmentCompat` 的分隔线取决于 `preferenceTheme`，而本仓库未显式配置；Compose 版未画分隔线，**需真机确认是否需要补 `HorizontalDivider`**。
- **尚未真机验证**：仅编译与打包通过。

### 5.5 Phase 2 批次划分（**已按代码实况修正**）

> ⚠️ 下表是在**逐个读过代码之后**修正的。最初的划分只按文件数估算，实际读代码后发现 P2-1 里
> 有相当一部分**不适合迁移**——这类"看起来是简单列表页、实则是 TextView 渲染或库 Fragment"的坑，
> 是排期时最容易低估的部分。

| 批次 | 目标 | 存量 | 适配性 | 说明 |
| --- | --- | --- | --- | --- |
| **P2-1** | `ui/about` | 7 | ✅ **已完成** | 见 5.4 |
| | `ui/font/FontSelectDialog` | 274+82 行 | ⚠️ **可迁但成本被低估** | 工具栏 + `RecyclerView` → Compose 本身不难，但有 **4 个源码断言测试**锁定了 Adapter/布局实现，必须一并重写（见下） |
| | `ui/dict/DictDialog` | 181+51 行 | ❌ **不建议迁移** | 内容用 `setHtml` + 自定义 `TagHandler`/`ImageGetter`，或 Markwon 渲染进 `TextView`。Compose 无等价物，只能 `AndroidView` 包一层——迁移收益为零 |
| | `ui/qrcode` | 4 个文件 | ❌ **不建议迁移** | `QrCodeFragment` 继承库里的 `BarcodeCameraScanFragment`（相机预览 + 解码），包进 Compose 有害无益 |
| **P2-2** | `ui/config`（17）、`ui/autoTask`（6） | 23 | ✅ 推荐 | 多为设置表单/列表，可直接复用 5.4 的 About 样板 |
| **P2-3** | `ui/highlight`（5）、`ui/replace`（7） | 12 | ✅ 推荐 | `Adapter` → `LazyColumn` |
| **P2-4** | `ui/book/search`、`ui/book/source/*`、`ui/association`（38） | — | ✅ 推荐 | 有 ViewModel + Flow，验证 `collectAsStateWithLifecycle` |
| **P2-5** | `ui/rss`（35） | 35 | ✅ | 同上 |
| **P2-6** | `ui/main`（27） | 27 | ⚠️ 靠后 | `ViewPager` + `BottomNavigationView` → `Scaffold` + `NavigationBar`，风险较高 |

### 5.6 对话框迁移方案（**设计已定，代码待需要时再落**）

`DialogFragment` 是迁移大头：全仓库 **85 个类**继承它，其中推荐下一批的 `ui/config` 就占 **8 个**。

**已定方案**：新增一个与 `BaseComposeActivity` 对称的基类，内容视图换成只放 `ComposeView` 的宿主布局，
从而复用 `BaseDialogFragment` 的全部既有能力（主题背景色、电子墨水边框、防重复 `show`、`finishOnDismiss`、`execute {}`）：

```kotlin
// res/layout/dialog_compose_host.xml —— 只放一个 ComposeView
abstract class ComposeDialogFragment : BaseDialogFragment(R.layout.dialog_compose_host) {
    final override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<ComposeView>(R.id.compose_view).setContent {
            LegadoComposeTheme { Content(savedInstanceState) }
        }
    }
    @Composable abstract fun Content(savedInstanceState: Bundle?)
}
```

用法：继承并实现 `Content()`；需要固定尺寸时仍在 `onStart()` 里 `setLayout(w, h)`（与 View 版一致）。

**一个已排掉、值得记录的坑**：`ComposeView` 需要 `ViewTreeLifecycleOwner`，若 `DialogFragment` 创建的是普通
`Dialog` 就会崩。已**反编译确认** fragment 1.8.9 的 `DialogFragment.onCreateDialog` 返回的正是
**`androidx.activity.ComponentDialog`**（它会在 decorView 上设置 ViewTree 系列 owner），
因此 `ComposeView` **开箱可用，不需要手写 owner**。

> 📌 **为什么现在只有方案、没有代码**：这段基类曾按预案写好，但当时零引用，
> 属于"预留"。按本文档坚持的原则（**等真正需要时再加，不预留**），已在清理中删除；
> 上面这段就是完整实现，等迁第一个对话框时按此落地即可。

### 5.7 ⚠️ 一个被低估的成本：源码断言测试

本项目有若干**读取源码文本做断言**的单元测试，它们把实现细节当成了契约。已遇到的：

| 测试 | 锁定的实现 | 影响 |
| --- | --- | --- |
| `UpdateDialogLifecycleTest` | `ui/about/AboutFragment.kt` 含 `"check_update" -> checkAppUpdate()` | About 迁移时需改路径（已处理） |
| `FontSelectionStyleTest` | `FontAdapter.kt` 含 `rootCard.background = GradientDrawable()...`、`tvFont.typeface = kotlin.runCatching`；`item_font.xml` 含 `@+id/root_card`；`FontSelectDialog.kt` 含 `adapter.setItems(it)`、`private fun mergeFontItems` 等 | **迁移 FontSelectDialog 需重写其中 4 个用例** |

**排期含义**：迁移一个界面时，除了界面代码本身，还要检查并重写这类"设计契约测试"。建议在动每个页面之前先 `grep` 它的文件名，成本可提前量化。

### 5.8 禁区（不迁移，用 `AndroidView` 包裹）

- `ui/book/read/page/**` —— 阅读排版引擎
- `ui/widget/**`（78 个文件）—— 自绘控件（`ExplosionAnimator`、`FastScroller`、`RecyclerViewAtPager2`、`WebtoonFrame` 等）
- `ui/video`（GSYVideoPlayer）、`ui/code`（Sora Editor）、`ui/book/audio`（LyricViewX）
- **`TextView` + Spannable 系内容渲染**：`ui/dict/DictDialog`（HTML/Markdown）、Markwon 相关页面——Compose 无等价物

**最小投入最大收益的做法**：完成 Phase 2 的非阅读器部分后，规定"所有新页面一律用 Compose"，阅读器永久保持 View 实现。

### 5.9 构建工具链升级（AGP 8.13.2 → 9.1.1）

本次同时完成了 AGP 大版本升级，目的是解锁最新的 Compose BOM。

| 项 | 升级前 | 升级后 | 原因 |
| --- | --- | --- | --- |
| AGP | 8.13.2 | **9.1.1** | Compose 1.12.x 要求 AGP ≥ 9.1.0；9.3.3 要求 Gradle ≥ 9.5.0，与 Gradle 9.3.1 不匹配 |
| Gradle | 8.14.4 | **9.3.1** | AGP 9 要求 Gradle 9；9.3.1 与 AGP 9.1.x 匹配，且仍是 Kotlin 2.4.10 支持范围内（≤9.5.0） |
| compileSdk | 36 | **37** | Compose 1.12.x 要求 compileSdk ≥ 37（SDK 37 采用次版本号，`37` 即 `37.0`） |
| Compose BOM | — | **2026.09.00**（Compose 1.12.1 / Material3 1.4.0） | 最新稳定 |

**AGP 9 的关键破坏性变更：内置 Kotlin 支持（built-in Kotlin）**

AGP 9.0 起内置 Kotlin 编译能力，`org.jetbrains.kotlin.android` 插件**必须移除**，否则报：

```
Failed to apply plugin 'org.jetbrains.kotlin.android'.
> The 'org.jetbrains.kotlin.android' plugin is no longer required for Kotlin support since AGP 9.0.
```

官方迁移要点（[Migrate to built-in Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin)）：

1. 从**模块级**与**顶层** build 文件移除 `kotlin-android`，并从 `libs.versions.toml` 的 `[plugins]` 中删除该条目；
2. `kotlin-kapt` 与内置 Kotlin 不兼容，需换 `com.android.legacy-kapt`——**本仓库用 KSP，不受影响**；
3. `android.kotlinOptions {}` → `kotlin { compilerOptions {} }`——**本仓库未使用**；
4. `kotlin.sourceSets {}` 不再支持，只能走 `android.sourceSets {}` 的 `kotlin` 源集——**本仓库未使用**；
5. `android.defaults.buildfeatures.*` 在 AGP 9.0 已移除（本仓库原先设了 5 个），应从 `gradle.properties` 删除。

**本仓库实际改动**：

- [app/build.gradle](../app/build.gradle)、[build.gradle](../build.gradle)、[modules/book/build.gradle](../modules/book/build.gradle)、[modules/rhino/build.gradle](../modules/rhino/build.gradle)：移除 `kotlin.android` 插件
- [gradle/libs.versions.toml](../gradle/libs.versions.toml)：删除 `kotlin-android` 插件条目，`agp` 升到 9.1.1，`composeBom` 设为 2026.09.00
- [gradle/wrapper/gradle-wrapper.properties](../gradle/wrapper/gradle-wrapper.properties)：`gradle-8.14.4` → `gradle-9.3.1`
- [build.gradle](../build.gradle)：`compile_sdk_version` 36 → 37
- [gradle.properties](../gradle.properties)：删除 5 个已移除的 `android.defaults.buildfeatures.*`；`org.gradle.unsafe.configuration-cache` 更名为 Gradle 9 的 `org.gradle.configuration-cache`
- [settings.gradle](../settings.gradle)：**移除 R8 版本钉制**

**关于 R8 钉制**：`settings.gradle` 原先通过 `pluginManagement.buildscript` 钉了 `com.android.tools:r8:9.1.29` 并加了 `r8-releases` 仓库，注释写明「Kotlin 2.4 metadata requires R8 9.1.29 or newer」——那是因为 AGP 8.13.2 自带的 R8 更旧。AGP 9.1.1 自带 **R8 9.1.31**（≥ 9.1.29），该钉制于是变成一次**降级**，并触发警告：

```
WARNING: Your project includes version 9.1.29 of R8, while Android Gradle Plugin was shipped with 9.1.31.
```

故移除钉制与那个仓库，直接使用 AGP 自带的 R8。

**保留可用**：`org.jetbrains.kotlin.plugin.compose` 与 `org.jetbrains.kotlin.plugin.parcelize` 在 AGP 9 内置 Kotlin 下**仍正常工作**（已编译验证），无需特殊处理。

**回退方案**：若必须暂时保留 `kotlin-android` 插件结构，可在 `gradle.properties` 设 `android.builtInKotlin=false` **并** `android.newDsl=false`（后者必需，因为 `kotlin-android` 与新 DSL 不兼容）。注意这是**临时**手段——AGP 10.0 将不再支持关闭内置 Kotlin。

**⚠️ 与未来 KMP 的冲突**：Kotlin 2.4.10 官方支持的 AGP 上限是 **9.1.0**，本次为解锁 Compose 1.12.x 升到 **9.1.1**，已略微越界。引入 KMP 时应把 **Kotlin 升到 2.4.20**（支持 AGP 至 9.3.1、Gradle 至 9.7.0）。详见第 4.2 节。

### 5.10 如何复现本次验证

本仓库的构建有几个环境前提，容易踩坑：

1. **JDK 21**：`app/build.gradle` 声明 `jvmToolchain(21)`，且工程未配置 toolchain 自动下载（settings.gradle 里没有 foojay resolver），因此**必须**有 JDK 21 可被发现。
2. **Android SDK**：仓库**没有** `local.properties`，且环境里 `ANDROID_HOME` 为空时构建会直接失败（`SDK location not found`）。用 Android Studio 打开会自动生成；命令行需要显式提供。
3. `~/.gradle` 必须可写（wrapper 发行版与依赖缓存都在那里）。

```bash
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=/path/to/Android/sdk

# 编译（验证 Compose 接入 + AGP 9 内置 Kotlin 迁移）
./gradlew :app:compileAppDebugKotlin --console=plain

# 打包（验证 AGP 9 的 R8 / dex / 资源压缩全链路）—— 注意用 release，不要用 debug
./gradlew :app:assembleAppRelease --console=plain
```

**本次验证结论**：

| 任务 | 结果 | 说明 |
| --- | --- | --- |
| `:app:compileAppDebugKotlin` | ✅ 通过 | Compose 1.12.1 + AGP 9.1.1 + 内置 Kotlin |
| `:app:assembleAppRelease` | ✅ 通过 | 产出 27 MB APK（4 个 dex，含 Compose 运行时类与原生库） |
| `:app:assembleAppDebug` | ❌ 失败 | **仓库既有问题**（5.11 节），与本次改动无关 |

**没有验证到的部分**（请不要当成已通过）：

- **运行时行为**：仅编译与打包通过，**未在设备/模拟器上运行过**。About 页的 Compose 顶栏、主题桥取色、以及 `AndroidView` 承载 `AboutFragment` 的实际渲染与 Fragment 恢复，都需要真机验证。
- **书源与排版回归**：完全未测。
- **`modules:book` / `modules:rhino` 的独立构建**：它们作为依赖参与了编译，但未单独跑测试。

> 注意 `app/so/`（Cronet 的 jniLibs 目录）在当前工作区不存在，仓库也没有配置 `jniLibs.srcDirs` 指向它；这不影响编译与 debug 打包，但正式出包前需确认 Cronet 原生库的注入方式。

### 5.11 ✅ 既有的 debug 打包阻塞点（**已解决**：`minSdk 23 → 26`）

**已通过 A/B 对照确认**：在未改动的 `HEAD`（AGP 8.13.2 / Gradle 8.14.4 / compileSdk 36）上执行 `:app:assembleAppDebug`，会以**完全相同的错误**失败：

```
ERROR: third_party/maven/org/htmlunit/htmlunit-core-js/5.3.0-legado.4/htmlunit-core-js-5.3.0-legado.4.jar:
D8: MethodHandle.invoke and MethodHandle.invokeExact are only supported starting with
Android O (--min-api 26): Lorg/htmlunit/corejs/javascript/SlotMapOwner$ThreadedAccess;->checkAndReplaceMap(...)
> Increase the minSdkVersion to 26 or above.
```

**根因（已定位到源码行）**：`third_party/maven/` 下的自定义 Rhino fork（`5.3.0-legado.4`，由提交 `175a15b94`「修复低版本系统 Java 类反射失败 (#868)」引入）中，`SlotMapOwner$ThreadedAccess` 使用了 `MethodHandles.lookup().findVarHandle(...)` 与 `VarHandle.compareAndExchange(...)`。D8 要求这类 `MethodHandle.invoke`/`invokeExact` 调用必须 `--min-api 26`，而本仓库 `minSdk 23`。

**不是 AGP 9 引入的**：D8 的这条限制与 AGP 版本无关。且 `legado.3`（2026-08-07 引入）里**同样存在**该结构——问题自 fork 引入起就存在，不是 #868 那次升版造成的。

**为什么只有 debug 失败**（A/B 证实）：release 的 R8 对同一问题只输出 **WARNING**，debug 的 D8 直接**报错**。所以在未改动的 `HEAD` 上：`assembleAppDebug` ❌、`assembleAppRelease` ✅。

**关键发现：该类是死代码。** fork 源码 `mgz0227/htmlunit-rhino-fork` 的
`rhino/src/main/java/org/mozilla/javascript/SlotMapOwner.java` 里，调用方**已被 HtmlUnit 官方注释掉**：

```java
protected static <T> SlotMap<T> createSlotMap(int initialSize) {
    /* HtmlUnit - avoid Context.getCurrentContext()
       we do not use Context.FEATURE_THREAD_SAFE_OBJECTS
    Context cx = Context.getCurrentContext();
    if ((cx != null) && cx.hasFeature(Context.FEATURE_THREAD_SAFE_OBJECTS)) {
        ... return THREAD_SAFE_EMPTY_SLOT_MAP / ThreadSafeHashSlotMap / ThreadSafeEmbeddedSlotMap;
    } else */
    if (initialSize == 0) {
        var res = (SlotMap<T>) EMPTY_SLOT_MAP;   // ← 实际永远走这里
```

即：线程安全分支永不进入，`ThreadedAccess` 永不执行；只是 D8 必须为 jar 内所有类生成 dex，因此**编译期**失败。Legado 侧也不启用该特性（全仓库搜索 `FEATURE_THREAD_SAFE_OBJECTS` 无业务命中）。

**✅ 最终决策：`minSdk 23 → 26`**（已实施并验证 `:app:assembleAppDebug` 通过）。

决策依据：`minSdk 26` = Android 8.0（2017），代价是放弃 Android 6.x/7.x。**佐证**：项目 CI 里早已为测试变体做过同样的绕过，说明团队此前已接受 26：

```groovy
// .github/scripts/source-browser-test.init.gradle
// Unshrunk HtmlUnit contains MethodHandle calls; this test runs on API 36.
variant.minSdk = 26
```

> 也就是说：**release 构建一直是好的**（R8 只告警），CI 的 debug 变体靠这个 init 脚本强制 26 也是好的，
> 唯独**本地 `assembleAppDebug` 用全局 minSdk 23**，所以一直坏着而没被 CI 发现。

**备选方案（未采用，留档）**：修 Rhino fork 而不动 minSdk。`ThreadedAccess` 已被 HtmlUnit 官方注释掉、是死代码，且它是 `SlotMapOwner` 的嵌套类，**本来就能直接访问私有的 `slotMap` 字段**（原本用 `MethodHandles.lookup()` 只是为了拿 `VarHandle`）：

```java
static final class ThreadedAccess {
    static <T extends PropHolder<T>> SlotMap<T> checkAndReplaceMap(
            SlotMapOwner<T> owner, SlotMap<T> oldMap, SlotMap<T> newMap) {
        // Android: VarHandle 需要 API 33；且该路径在本项目不会执行（见 createSlotMap）
        SlotMap<T> current = owner.slotMap;
        if (current == oldMap) {
            owner.slotMap = newMap;
        }
        return current;
    }
}
```

再按 `SOURCE.md` 记录的仓库与命令重建为 `legado.5`：

```shell
# Rhino: mgz0227/htmlunit-rhino-fork@76460c0...  打包层: mgz0227/htmlunit-core-js@3eb5071...
mvn --batch-mode -T 1 -U clean install -Dmaven.test.skip=true -Dgpg.skip=true \
    -Dmaven.javadoc.skip=true -Dmaven.compiler.showWarnings=false
```

若将来需要重新支持 Android 6/7，可走这条路（本机无 Maven，需先安装）。

**为什么它优先级高**：`assembleAppDebug` 挂掉意味着**无法安装 debug 包**，而 release 包未签名也不可直接安装——于是**任何新 Compose 页面都无法真机验证**。这直接卡住第 5 节的迁移工作。

### 5.12 ⚠️ AGP 9 引入的 R8 行为变化（**已修复**）

这是本次升级中**唯一一处因升级而破坏既有构建**的地方，必须记录：

| 构建 | 未改动的 HEAD（AGP 8.13.2） | 升级后（AGP 9.1.1） |
| --- | --- | --- |
| `:app:assembleAppDebug` | ❌ D8 MethodHandle（既有问题） | ❌ 同样错误 → **非升级引起** |
| `:app:assembleAppRelease` | ✅ **成功** | ❌ `minifyAppReleaseWithR8` 失败 → **升级引起的回归** |

AGP 9 的新版 R8 把「缺失类」从**警告**升级为**构建错误**。报错内容：

```
ERROR: Missing classes detected while running R8.
Missing class kotlin.reflect.full.KCallables (referenced from: io.ktor.server.engine.internal.AutoReloadUtilsKt...)
Missing class kotlin.reflect.full.KClasses   (referenced from: io.ktor.server.engine.internal.CallableUtilsKt...)
Missing class kotlin.reflect.jvm.ReflectJvmMapping (referenced from: io.ktor.server.engine.ServerHostUtilsKt...)
```

**背景**：本仓库刻意排除了 `kotlin-reflect`（[app/build.gradle](../app/build.gradle) 中 `//implementation(libs.kotlin.reflect)` 被注释，且对三个依赖做了 `exclude group: 'org.jetbrains.kotlin', module: 'kotlin-reflect'`），但 Ktor server 的开发模式自动重载 / 模块反射加载会引用这些类。这些路径在 release 运行时不会执行。

**修复**：按 R8 生成的 `app/build/outputs/mapping/appRelease/missing_rules.txt`，在 [app/proguard-rules.pro](../app/proguard-rules.pro) 中补上：

```proguard
-dontwarn kotlin.reflect.full.KCallables
-dontwarn kotlin.reflect.full.KClasses
-dontwarn kotlin.reflect.jvm.ReflectJvmMapping
```

**给后续升级者的提醒**：升 AGP 大版本后，除了看编译是否通过，**必须**跑一次 `assembleRelease`——R8 的行为变化（缺失类、keep 规则、优化策略）只在 release 路径暴露，debug 编译完全测不出来。

---

## 6. 互操作手册

### 6.1 Compose 作为页面根（新页面）

继承 `BaseComposeActivity`，实现 `Content()`：

```kotlin
class FooActivity : BaseComposeActivity() {
    @Composable
    override fun Content(savedInstanceState: Bundle?) {
        // 已自动包在 LegadoComposeTheme 内
    }
}
```

### 6.2 Compose 嵌入现有 View 页面（改造老页面，最常用）

在现有 XML 中加 `ComposeView`，配合已有的 `viewBinding` 直接使用：

```xml
<androidx.compose.ui.platform.ComposeView
    android:id="@+id/compose_view"
    android:layout_width="match_parent"
    android:layout_height="wrap_content" />
```

```kotlin
binding.composeView.setContent {
    LegadoComposeTheme { SomeContent() }
}
```

### 6.3 View/Fragment 嵌入 Compose（保护既有资产）

```kotlin
AndroidView(factory = { ctx -> MyLegacyView(ctx) })
```

Fragment 需要**稳定容器 id**，否则配置变更后 Fragment 无法恢复——先在 `res/values/ids.xml` 里声明一个，再在 `AndroidView` 里使用：

```xml
<!-- res/values/ids.xml -->
<item name="my_fragment_container" type="id" />
```

```kotlin
AndroidView(
    factory = { ctx -> FragmentContainerView(ctx).apply { id = R.id.my_fragment_container } },
    update = { view ->
        val fm = context.findFragmentActivity()?.supportFragmentManager ?: return@AndroidView
        if (fm.findFragmentByTag(TAG) == null) {
            fm.commit { setReorderingAllowed(true); replace(view.id, MyFragment(), TAG) }
        }
    },
)
```

> 注：此前为 About 页的 View 孤岛声明过 `compose_fragment_container`，但 About 页已改为纯 Compose，
> 该 id 无使用点、已在清理中删除。**等真正需要嵌 Fragment 时再声明**，不要预留。

### 6.4 状态层

| 现状 | Compose 侧 |
| --- | --- |
| `Flow`（83 个文件） | `collectAsStateWithLifecycle()`（已加 `lifecycle-runtime-compose`） |
| `LiveData`（42 个文件） | `observeAsState()`（已加 `runtime-livedata`） |
| `LiveEventBus`（62 个文件） | 保持不变；需要时用 `callbackFlow` 包一层 |

### 6.5 现有 `ViewBinding` 复用

已加 `compose-ui-viewbinding`，可在 Compose 内用 `binding<XxxBinding>()` 直接复用既有布局，避免重写大量条目 UI。

---

## 7. （存档）Spike（跨平台可行性验证）

> 🗄️ **KMP 已暂缓**，本节的 Spike 暂不启动。Spike-1 的静态部分（书源扫描）已完成，见 4.9 节。

**（暂缓）若将来重启跨平台，动手前必须先完成这两个验证。** 经第 4 节核实后，Spike 的重心已经从"UI 能不能做"转移到"书源兼容率能不能接受"。

### Spike-1：书源兼容率（**go/no-go 判据**）

> **状态更新：静态部分已完成**。第 4.9 节的实测统计（3310 条真实书源）就是本 Spike 的第一步，
> 结论已经把焦点从"JS 能不能跑"转移到 **XPath 能不能处理**。剩下的才是需要写代码的部分。

**已完成：规则类型分布与互操作语法统计** → 结果见 4.9 节。要点：XPath 影响 40.2% 书源、硬 Java 互操作仅 1.2%、需重建的宿主 API 共 51 个方法。

**待完成 —— 按优先级：**

| 子项 | 要回答的问题 | 为什么它最关键 |
| --- | --- | --- |
| **1a. XPath 改写可行性** | 24,069 个 XPath 片段中，有多少能用等价的 CSS 选择器表达？不能的那些用了什么能力（`//` 轴、谓词、`text()`、函数、`position()`）？ | **头号阻塞**：JsoupXpath 无法移植，只能改写或自研子集。40.2% 书源受影响 |
| **1b. 自研 XPath 子集的性价比** | 若实现一个覆盖 90% 用法的 XPath 1.0 子集（基于 Ksoup 的 DOM），工作量与风险如何？ | 决定 1a 是"改写书源"还是"写引擎" |
| **1c. 宿主绑定层重建** | 51 个 `java.*` 方法在目标平台的实现成本；哪些依赖 Android 独占能力（`startBrowserAwait`/`webView`/`getVerificationCode`/`androidId`）？ | 26.3% 书源需要；但面窄、可排期 |
| **1d. JS 引擎语义验证** | 换到 quickjs-kt / JavaScriptCore 后，抽样书源的 JS 能否原样执行？ | 常规验证，风险已降低 |

**验收标准**：
1. 给出 **XPath 改写覆盖率**（可等价改写 / 需自研子集 / 无法支持 三类的片段数与占比）——这是 go/no-go 的核心数字；
2. 给出宿主层 51 个方法的实现清单与平台依赖标注；
3. 给出抽样书源在候选 JS 引擎上的执行通过率。

**失败后果**：iOS 端降级为"仅支持无 XPath 的书源"（即 58.7% 那部分），或整个 CMP 路线否决。

**重要提示**：`Packages.*` / `JavaImporter` 在 QuickJS/JavaScriptCore 中**没有对应物**（4.5 节），
所以那 1.2% 的书源（crypto 签名类：七猫、连城、晋江等）只有两条路——把 crypto 能力**提升为宿主方法**
（如 `java.aesDecode(...)`），或明确不支持。不要指望某个 JS 引擎能直接跑它们。

### Spike-2：Compose 文本 API 能否重建中文分页排版

- **问题**：`TextMeasurer` / `TextLayoutResult` / `drawText` 是否足以复现现有断行、分页、绝对定位与高亮绘制？
- **已知结论**（第 4.7 节，已核实）：这些 API 都是 **Common** 且能力齐备，**但缺少 StaticLayout 那种"逐行增量重排、按行索引外部驱动"的公开低阶 API**，重排必须整体重新 `measure`。
- **方法**：取同一章正文，用现有引擎与 Compose 方案分别排版，逐页比对行数、断点、坐标。
- **验收**：像素级或行级一致；给出不一致场景清单（标点挤压、悬挂标点、两端对齐、高亮跨行等），并给出"分块 measure + 自管缓存"的性能结论（超大章节的翻页耗时）。
- **失败后果**：排版引擎需按平台各写一份，CMP 的"一套 UI"收益大幅缩水（但注意：**排版引擎本来就是 Android 独占的 View 代码，iOS 无论如何都要新写**，所以这个 Spike 的失败不会否决 CMP，只会影响共享程度）。

---

## 8. （存档）跨平台分阶段路线图

> 🗄️ **KMP 已暂缓**。下表 S0–S4 属于跨平台路线；
> **在纯 Android 路线上，唯一仍在进行的是 S5（UI 渐进式迁移），即第 5 节。**

| 阶段 | 内容 | 是否与平台选择无关 |
| --- | --- | --- |
| S0 | 两个 Spike | 无关（必须先做） |
| S1 | 解耦 `data/`、`model/` 对 Android 的依赖（`Context` 注入化、IO 抽象） | **无关**，纯 Android 也有收益 |
| S2 | 模块化：`core`（规则引擎/数据/工具）与 `platform`（平台实现）分离 | **无关** |
| S3 | 引入 KMP，把 `core` 移入 `commonMain`，跑通 Android 侧 | 无关 |
| S4 | 目标平台落地：CMP 抽取 UI 到 `commonMain`，或按 Spike 结论调整 | 有关 |
| S5 | UI 渐进式迁移（可提前并行，见第 5 节） | 与 S4 选择耦合 |

**关键判断**：S5（UI 迁移到 Compose）在 S0/S4 之前做是有风险的——如果最终选 Flutter，这部分投入作废。S1/S2 则无论选哪条路都必须做。

---

## 9. 工作量与风险（粗略）

| 方案 | 范围 | 粗略人月 | 主要风险 |
| --- | --- | --- | --- |
| **Compose 渐进迁移（当前方案）** | **非阅读器 UI** | **3–5** | **低，可灰度、可随时回退** |
| Compose 渐进迁移（当前方案） | 含阅读器 | 6–12 | 排版回归，**不建议**（阅读器保持 View） |
| 🗄️ KMP + CMP 到 iOS | 含 core 抽取 + iOS 排版 | 15–30+ | Spike 可能否决路线 |
| 🗄️ Flutter 重写 | 全部 | 30–60+ | 书源兼容性、排版、生态重建 |

（估算基于 68 Activity / 124 Fragment / 89 Dialog / 104 Adapter 的存量，不含测试。Legado 的排版回归与书源兼容性测试成本往往超过 UI 改写本身。）

**当前方案（Android Compose）的推进节奏建议**：不要一次性铺开。先做 P2-2 的 2 个小包验证流程，再按 5.5 节顺序推进；每批合并后真机回归一次。

---

## 10. 待决策问题

### 当前（Android Compose）

**已决策**：

- ✅ **`assembleAppDebug` 阻塞**：采用 `minSdk 23 → 26`，已验证 debug APK 可正常产出（5.11 节）。
- ✅ **About 页偏好列表**：已用纯 Compose 重写，`AboutFragment` 与 `R.xml.about` 已删除（5.4 节），可作为 `ui/config` 等设置类页面的样板。

**待决策**：

1. 顶栏用 Material3 `TopAppBar` 后与旧 `TitleBar` 有细微视觉差异，是否接受？这决定新页面是"自绘顶栏"还是"统一用 TopAppBar"。
2. 偏好列表是否需要**分隔线**？原 `PreferenceFragmentCompat` 的分隔线取决于 `preferenceTheme`（本仓库未显式配置），Compose 版未画。需真机对照后再定。
3. 是否引入 **Compose 预览/截图测试**？104 个 Adapter 改 `LazyColumn` 后，没有截图测试很难防回归。
4. **单元测试基线**：`testAppDebugUnitTest` 存在 **9 个既有失败**（`android.util.Log not mocked`，共 1948 个用例），且 `.github/workflows` 里**没有**跑单元测试的任务。是否补 `testOptions.unitTests.isReturnDefaultValues = true` 并把单元测试纳入 CI？

### 🗄️ 存档（跨平台，重启时再议）

5. iOS/桌面的**必须功能边界**？JS 书源、本地 TTS、Web 服务、MCP、EPUB/PDF 是否都必须可用？
6. 是否接受 iOS 端**排版引擎单独实现**（而非共享）？
7. 是否接受 iOS 端**不支持 XPath 书源**（实测影响 40.2%）或仅支持改写后的子集？
8. 书源兼容性是否有**自动化回归基线**？
