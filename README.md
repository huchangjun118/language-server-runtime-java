# minils-java

一个可独立运行的语言服务器（Language Server），通过标准输入输出与编辑器客户端
交换 LSP 消息，为一种极简的声明式配置文本提供**诊断**、**跳转定义**与**查找引用**。

## 分析对象：minils 语言

每个非空行由「关键字 + 标识符」组成：

```
def apple      # 定义标识符 apple
def banana
ref apple      # 引用前面已定义的标识符
```

- `def <identifier>`：定义。重复定义产生 `duplicate-definition` 错误诊断。
- `ref <identifier>`：引用。引用未定义的标识符产生 `undefined-reference` 错误诊断。
- 其他关键字：产生 `unknown-keyword` 警告诊断。

诊断的 `source` 为 `minils`，三种诊断通过 `code` 字段互相区分。

## 构建与运行

```bash
mvn package          # 产出 target/language-server-runtime-java-1.0-SNAPSHOT.jar
java -jar target/language-server-runtime-java-1.0-SNAPSHOT.jar
```

服务器通过 stdin/stdout 通讯；所有日志只写 stderr，stdout 专用于协议报文。
客户端发送 `shutdown` 请求后再发送 `exit` 通知，进程以退出码 0 结束；
未经过 `shutdown` 直接 `exit` 时退出码为 1。

## 支持的协议能力

| 能力 | 方法 | 说明 |
| --- | --- | --- |
| 生命周期 | `initialize` / `initialized` / `shutdown` / `exit` | 初始化前的请求返回 `-32002`（ServerNotInitialized）；shutdown 后的请求返回 `-32600`（InvalidRequest）；重复 initialize 被拒绝 |
| 文档同步 | `textDocument/didOpen` / `didChange` / `didClose` | `textDocumentSync.change = 2`（Incremental）。支持全量替换与按范围替换；同一通知内的多个编辑按数组顺序依次应用；版本号以客户端为准（回退/跳号不阻塞应用）；同一文档的变更严格按到达顺序生效 |
| 诊断 | `textDocument/publishDiagnostics` | 打开/变更后异步（去抖）发布；过期版本的计算结果会被丢弃，不会覆盖更新状态；关闭文档时发布空诊断以清空 |
| 跳转定义 | `textDocument/definition` | 标识符首次定义处的 Location；未定义时返回 `null` |
| 查找引用 | `textDocument/references` | 所有引用位置；`context.includeDeclaration` 为真时包含定义位置 |
| 取消 | `$/cancelRequest` | 被取消的请求返回 `-32800`（RequestCancelled），且之后不会再产生该请求的结果；每个请求恰好响应一次 |

并发模型：单读取循环保证通知按序生效；请求在线程池上并发执行，响应可乱序但
按 `id` 对应；慢请求不会阻塞后续请求与通知；诊断在独立调度线程上计算。
未知方法返回 `-32601`，未知通知静默忽略。

## 基础协议与错误恢复

报文严格遵循基础协议分帧：`Content-Length: <n>\r\n\r\n<body>`，声明长度与
实际读取字节数一致。`Content-Type` 头被接受并忽略。

- 非法报文头、垃圾字节、非法/超大 `Content-Length`、截断的报文体：
  服务器以 `id: null`、错误码 `-32700`（ParseError）返回能定位问题的错误信息，
  随后重新扫描流中的下一个 `Content-Length:` 头进行重同步，继续处理后续消息，不崩溃。
- 最大报文长度可配置（见下），超过即视为分帧错误。

## 位置编码策略

- 支持 LSP 3.17 的 `positionEncoding` 协商：`utf-8`、`utf-16`（默认）、`utf-32`。
- 客户端在 `initialize` 的 `capabilities.positionEncoding` 中声明偏好；
  服务器支持则采纳并在结果中回显，否则回退 `utf-16`。
- 所有行列换算按码点（code point）进行：UTF-16 单元 1/2、UTF-8 字节 1–4、
  UTF-32 恒为 1。代理对（如 emoji）与组合字符（如 `e` + U+0301）不会产生偏移；
  落在码点中间的单位偏移会钳制到该码点起始处，绝不拆分代理对。
- 行以 `\r\n`、`\r` 或 `\n` 切分；越界行列会被钳制到文档范围内。

## 配置项（系统属性）

| 属性 | 默认值 | 说明 |
| --- | --- | --- |
| `lsp.diagnosticsDelayMs` | `50` | 诊断发布前的去抖延迟（毫秒）；期间出现的新变更会取代旧计算 |
| `lsp.computeDelayMs` | `0` | definition/references 计算内的人工延迟（毫秒），按 10ms 切片、可被取消中断，用于测试取消与背压 |
| `lsp.maxMessageSize` | `67108864`（64 MiB） | 单条报文 `Content-Length` 上限，超出按分帧错误处理 |

示例：`java -Dlsp.diagnosticsDelayMs=100 -jar target/language-server-runtime-java-1.0-SNAPSHOT.jar`

## 测试

```bash
mvn test
```

测试在进程内通过管道流驱动真实服务器，覆盖：

- **FramingTest**：垃圾字节、非法/超大 Content-Length、截断报文体、非 JSON 报文、
  Content-Type 头、错误后的重同步恢复。
- **LifecycleTest**：初始化前/关闭后请求的可区分错误、退出码 0/1、未知方法、重复初始化。
- **PositionEncodingTest**：UTF-8/16/32 单元换算、代理对与组合字符、码点不拆分、
  跨行与行尾位置、越界钳制、offset↔position 往返一致性、编码协商生效。
- **DocumentSyncTest**：全量/增量变更、单通知内乱序与重叠编辑按序应用、
  版本回退与跳号、跨行编辑、行尾编辑、UTF-8 下的范围编辑、关闭移除文档。
- **DiagnosticsAndNavigationTest**：重复定义/未定义引用/未知关键字三类可区分诊断、
  变更后更新、关闭时清空、过期诊断不发布、定义/引用/空结果。
- **CancellationConcurrencyTest**：取消返回 `-32800` 且不再产生结果、取消未知 id、
  并发请求全部按 id 应答、慢请求不阻塞通知与后续请求、同一文档变更按到达顺序生效。
