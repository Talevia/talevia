## 2026-04-21 — provider-auth-state：引入 `ProviderAuth` + `EnvProviderAuth`，把散落的 `env["*_API_KEY"]?.takeIf(...)` 逻辑集中（VISION §5.2）

Commit: `25d8b70` (pair with `docs(decisions): record choices for provider-auth-state`).

**Context.** 5 个 composition root（CLI / Desktop / Server / Android / iOS）每个都有一串一模一样的 `env["OPENAI_API_KEY"]?.takeIf { it.isNotBlank() }?.let { ... }`——CLI 有 6 处（image/asr/tts/video/vision/search）、Desktop 有 7 处、Server 有 7 处 + InMemorySecretStore 构造里再 2 处、Android 有 1 处 block 在 ProviderRegistry 构造里、iOS 1 处在 `IosBridges.buildIosProviderRegistry`。每加一个新 provider 要改 5 个文件；每 UI 想展示"missing X key"要自己重新解析 env var 命名约定。

VISION §5.2 rubric "Provider-related surfaces — tool / provider / 注册"是目前打"部分"分的轴：provider registry 已有 `LlmProvider` / `ProviderRegistry`，但**auth 状态**没抽象层，散落在容器里。OpenCode 的 `packages/opencode/src/provider/auth.ts` 把这个集中管理——**只抽行为**，拒绝 Effect.js Service/Layer 结构（CLAUDE.md 红线）。

**Decision.** 新增 `core.provider.ProviderAuth` 抽象 + `EnvProviderAuth` 实现，把 5 个容器的 `env[...]?.takeIf(...)` 替换成 `providerAuth.apiKey(providerId)` 调用。

```kotlin
@Serializable sealed class AuthStatus {
    data object Present : AuthStatus()
    data object Missing : AuthStatus()
    data class Invalid(val reason: String) : AuthStatus()
}

interface ProviderAuth {
    fun authStatus(providerId: String): AuthStatus
    fun apiKey(providerId: String): String?  // Present → 返回 key，否则 null
    val providerIds: List<String>
}

class EnvProviderAuth(
    private val envLookup: (String) -> String?,
    private val envVars: Map<String, List<String>> = DEFAULT_ENV_VARS,
) : ProviderAuth { ... }
```

`DEFAULT_ENV_VARS` 固化了 5 个已用 provider 的 env var 映射：

| providerId | env vars（按优先级） |
|---|---|
| `anthropic` | `ANTHROPIC_API_KEY` |
| `openai` | `OPENAI_API_KEY` |
| `google` | `GEMINI_API_KEY`, `GOOGLE_API_KEY`（第一个非空胜出） |
| `replicate` | `REPLICATE_API_TOKEN` |
| `tavily` | `TAVILY_API_KEY` |

三态 `AuthStatus`（Present / Missing / Invalid）而非二元是关键设计点（§3a.4 显式拒绝二元化）：
- 对**未设置**（`null` / 整体 blank）→ `Missing`。UI prompt "添加你的 key"。
- 对**设置了但内容有问题**（空串 + 其他 alias 也空 → `Invalid("set but blank")`；内含空格 / 换行 → `Invalid("contains whitespace — likely malformed")`）→ UI prompt "你的 key 似乎坏了"。
- 对**unknown providerId**（被 query 的 id 不在 `envVars.keys`）→ `Invalid("unknown provider")`。

`envLookup: (String) -> String?` 是注入点——让 commonMain 零平台依赖：
- JVM 容器：`EnvProviderAuth(env::get)`（env 是 `Map<String, String>` = `System.getenv()`）。
- Android：`EnvProviderAuth { name -> System.getProperty(name) ?: System.getenv(name) }`（两层查找保留原有行为）。
- iOS：`EnvProviderAuth { name -> NSProcessInfo.processInfo.environment[name] as? String }`（via iOS-side `iosProviderAuth()` factory 保留在 `IosBridges.kt`）。

**5 composition root 更新**：
- `apps/cli/src/main/kotlin/io/talevia/cli/CliContainer.kt`：6 个 engine 字段从 `env["OPENAI_API_KEY"]?.takeIf...` → `providerAuth.apiKey("openai")`；2 个 Replicate → `apiKey("replicate")`；1 个 Tavily → `apiKey("tavily")`。
- `apps/desktop/src/main/kotlin/io/talevia/desktop/AppContainer.kt`：同样 9 处替换，KDoc 保留（语义一致）。
- `apps/server/src/main/kotlin/io/talevia/server/ServerContainer.kt`：9 处 + SecretStore 构造里 2 处（`env["ANTHROPIC_API_KEY"]?.takeIf(String::isNotEmpty)` → `providerAuth.apiKey("anthropic")`）。
- `apps/android/src/main/kotlin/io/talevia/android/AndroidAppContainer.kt`：新增 `providerAuth` 字段（通过 `System.getProperty ?: System.getenv` lookup 保留 Android 的 propery-first-then-env 行为）；`ProviderRegistry.Builder().addEnv(...)` 的构造小幅清理（不用改方向，它读的是 env var 到 value 的 full map）。
- `core/src/iosMain/kotlin/io/talevia/core/IosBridges.kt`：新增 top-level `iosProviderAuth()` 工厂函数，SwiftUI AppContainer 未来消费。`buildIosProviderRegistry` 保留现状（同 Android 理由：它构造的是 envName→value map）。

**无新 LLM tool**：`ProviderAuth` 是 Core 内部 API，不是 LLM 可见 tool。Tool count 0 变化。LLM context token 成本 0。

**Alternatives considered.**

1. **Option A (chosen)**: `ProviderAuth` interface + `EnvProviderAuth` 唯一实现，以 `(String) -> String?` 回调注入 env 读法。优点：commonMain 零平台依赖；三态 Present/Missing/Invalid 对 UI 友好；provider id → env vars map 配置化（加新 provider 只改一行 default map）；测试用 `Map::get` lambda 一行搞定。缺点：多一个接口层；但历来抽象 provider auth 的议题只会越来越复杂（OAuth / token 刷新 / 多账号），现在打底更划算。
2. **Option B**: 保留容器散落代码，只把 env var 常量集中到 `core.provider.ProviderEnvVars` object。拒绝：常量集中不解决"5 处做一样的事"——第一次运行时发现 key 缺失，UI 仍要自己重新跑 lookup。VISION §5.2 "UI 能显示具体缺什么"无解。
3. **Option C**: 做成 `object ProviderAuth { fun apiKey(providerId, env: Map<String, String>): String? }` 纯函数 / singleton。拒绝：难以 mock；Android 需要叠加 System.getProperty 和 System.getenv 两层查找，纯函数 API 就要把这个传成参数 → 回到 scattered pattern。实例化接口 + 回调注入更干净。
4. **Option D**: 把 auth 嵌入 `ProviderRegistry`——`registry.authStatus("openai")`。拒绝：`ProviderRegistry` 是 `LlmProvider` 注册表，职责是"该 providerId 的 stream/dispatch 怎么走"；auth 是"能不能 stream"的前置。耦合进去混淆两个关注点。分离后 UI 可以先查 `providerAuth` 决定要不要让用户看到"试试 X provider"的入口，再决定要不要构造 `registry`。
5. **Option E**: 让 `ProviderAuth.authStatus` 返回一个 HTTP ping 结果（"is the key valid against the real service"）。拒绝：把 network call 放在本应 O(1) 的 query 里会让 UI refresh 变慢；而且 "valid against API" 只能在实际发 stream 请求时才 100% 确定。目前的 `Invalid` 只做本地 cheap 格式检查，这是对的——"真实有效性"是调用方运行时的问题。
6. **Option F**: 把 `providerAuth` 绑到 `BusEvent`（`AuthStatusChanged`）供 UI 通过 EventBus 订阅。拒绝：auth 是读时查询，不是持续 mutation——env 不会在 runtime 中途变化（容器启动时快照 env 后就静态了）。EventBus 开销空耗。如果未来支持 runtime 加 key（用户粘贴后即时生效），那时再加 bus 事件。

**Coverage.**

- 新增 `EnvProviderAuthTest`（10 tests）：
  - `presentKeyReportsPresentAndReturnsValue` — 基本 happy path。
  - `missingKeyReportsMissing` — 完全未设置 → `AuthStatus.Missing`。
  - `blankKeyReportsInvalid` — 空白字符串 → `AuthStatus.Invalid("set but blank")`。
  - `whitespaceInsideKeyReportsInvalid` — 内嵌空格 → `Invalid("contains whitespace")`。
  - `trailingNewlineReportsInvalid` — 尾随 `\n`（常见 `echo $KEY > file` bug）→ Invalid。
  - `unknownProviderReportsInvalid` — 未配置的 providerId → `Invalid("unknown provider")`。
  - `googleHonoursBothAliases` — `GEMINI_API_KEY` / `GOOGLE_API_KEY` 任一命中都 OK。
  - `firstAliasWinsWhenBothSet` — 两个都设置时按 `DEFAULT_ENV_VARS` 顺序（GEMINI 先）胜出。
  - `blankFirstAliasFallsThroughToSecond` — 第一 alias 空串时不 break loop，继续尝试第二 alias。
  - `providerIdsListsDefaults` + `customEnvVarsMapOverridesDefault` — 覆盖默认 map 也能工作。
- `./gradlew :core:jvmTest` 全绿。
- `./gradlew :core:ktlintCheck` 全绿。
- 4 端构建：iOS sim / Android APK / Desktop / Server / JVM core 全部通过。容器的 engine 字段构造完全保留原有 null-vs-engine 行为。

**Registration.** 不是 LLM tool，不走 ToolRegistry。但是 5 个 composition root 各自的 `providerAuth: ProviderAuth` 字段引入 → UI 层未来可以注入消费。

**§3a 自查.**
1. Tool count: 0 变化（纯 Core infrastructure）。PASS。
2. Define/Update: N/A。
3. Project blob: 不动 Project。PASS。
4. 状态字段: `AuthStatus` 是 **3 态 sealed**（Present / Missing / Invalid），不是二元 —— 显式回避了 §3a.4 的二元化陷阱。`Invalid` 还带 `reason: String` 解释为啥 invalid，比简单 `false` 信息量更足。PASS。
5. Core genre: 无 genre 名词，provider id（anthropic / openai / google / replicate / tavily）都是运行时值，不是 Core 一等类型。PASS。
6. Session/Project binding: N/A。
7. 序列化向前兼容: `AuthStatus` 被 `@Serializable` 标记（可传过 SSE / cross-module），所有 subtype 字段有 default 或唯一必填（`Invalid.reason`）。增加 provider id 或 env alias 不改 schema。PASS。
8. 5 端装配: 5 个 composition root 全部同步更新。PASS。
9. 测试语义覆盖: 10 个 test 覆盖 Present / Missing / Invalid 三分支 + alias fallback + trailing-newline corner case + unknown provider 错误路径。PASS。
10. LLM context 成本: 0（不是 LLM tool）。PASS。

**Non-goals / 后续切片.**
- UI 层消费（Compose desktop / SwiftUI / Android Compose）显示 "missing X key" banner：下一轮 UI 完善时接入 `providerAuth.authStatus(...)`，这次只提供 Core 层 API。
- 把 `ProviderRegistry.Builder().addEnv(...)` 也用 `ProviderAuth` 替代（现在还是直接读 env var map）：两者语义略有差异（registry builder 要 envName→value 全 map 做某种 provider discovery），融合需要修改 `ProviderRegistry` 内部。留待 `provider-auth-state` 自身稳定后再动。
- 把 `REPLICATE_MUSICGEN_MODEL` / `REPLICATE_UPSCALE_MODEL` 这类 "model override" 类 env var 纳入 `ProviderAuth` 抽象：它们不是 auth 而是配置，现在各容器直接 `env["..."]?.takeIf(...)` 读是合理的。如果配置类 env 也集中需要，单独引入 `ProviderConfig` 抽象，不扩 `ProviderAuth`。
- OAuth / token 刷新 / 多账号：现在 env var 单一静态 key 够用；需要多账号的时候加 `TokenAuth : ProviderAuth` 另一个实现，接口不变。
