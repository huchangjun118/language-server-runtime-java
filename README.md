# mini-config Language Server

一个可独立运行的语言服务器，通过标准输入输出（stdio）与编辑器客户端按
LSP 基础协议（`Content-Length` 分帧的 JSON-RPC）通信，为一种极简的声明式
配置文本提供**诊断**、**跳转定义**与**查找引用**能力。

## 目标语言

每个非空行由「一个关键字 + 一个标识符」组成：

```
def alpha        # 定义标识符 alpha
ref alpha        # 引用前面已定义的 alpha
```

- 关键字仅支持 `def`（定义）与 `ref`（引用）。
- 标识符为 Unicode 字母开头的词（`[\p{L}_][\p{L}\p{N}_]*`）。
- 引用必须指向**前面行**已经定义过的标识符。

诊断码（`Diagnostic.code`，source 为 `mini-config`）：

| code                  | 含义                                   |
|-----------------------|----------------------------------------|
| `malformed-line`      | 行不是「关键字 + 标识符」两段结构       |
| `unknown-keyword`     | 关键字不是 `def` / `ref`               |
| `invalid-identifier`  | 标识符不合法                           |
| `duplicate-definition`| 标识符被重复定义（报在重复处）          |
| `undefined-reference` | 引用了此前未定义的标识符                |

## 运行

```bash
mvn package
java -cp target/language-server-runtime-java-1.0-SNAPSHOT.jar:<jackson jars> \
    com.github.huchangjun118.languageserverruntimejava.Application
```

进程从 stdin 读取分帧报文、向 stdout 写出响应与通知；stdout 只承载协议
报文，日志请走 stderr。收到 `exit` 通知后进程退出：此前收到过 `shutdown`
请求则退出码为 0，否则为 1。

## 支持的协议能力

`initialize` 返回的服务端能力：

```json
{
  "capabilities": {
    "positionEncoding": "utf-16 | utf-8 | utf-32（协商结果）",
    "textDocumentSync": { "openClose": true, "change": 2 },
    "definitionProvider": true,
    "referencesProvider": true
  }
}
```

- **生命周期**：`initialize` → `initialized` →（会话）→ `shutdown` → `exit`。
  - `initialize` 之前的请求（除 `initialize` 本身）返回 `-32002`
    （ServerNotInitialized）。
  - `shutdown` 之后的请求返回 `-32600`（InvalidRequest），通知被丢弃。
  - 未知方法请求返回 `-32601`；未知通知按 JSON-RPC 忽略。
- **文档同步**：`textDocument/didOpen`、`didChange`（全量与增量，
  `change = 2` 即 Incremental）、`didClose`。
  - 同一通知内的多个范围编辑可以**乱序、重叠**：服务端统一换算到变更前
    文本的偏移，按文档位置自后向前应用，重叠部分裁剪，结果确定。
  - **版本号回退或重复**（`version <= 当前版本`）的通知被忽略；跳号正常
    接受，文档内容不会因此偏离客户端状态。
  - 同一文档的变更通知严格按到达顺序生效（单线程串行应用）。
- **诊断**：`textDocument/publishDiagnostics` 在打开/变更后**异步**发布；
  计算期间文档又变更或关闭时，旧的（过期的）结果会被丢弃，不会覆盖更新
  的结果；`didClose` 时发布空诊断清空。
- **导航**：`textDocument/definition`（标识符处返回其 `def` 位置，未定义
  引用返回 `null`）、`textDocument/references`（返回该标识符全部出现位置，
  尊重 `context.includeDeclaration`）。
- **取消**：`$/cancelRequest`。已取消的请求返回 `-32800`
  （RequestCancelled）且之后不会再产生该请求的结果；请求开始前就收到的
  取消同样生效。
- **并发**：请求在线程池中并发处理，响应允许乱序但按 `id` 对应；慢请求
  不会阻塞后续请求与通知；工作线程均为守护线程，退出时统一回收，无泄漏。
- **容错**：非法报文头、非法 `Content-Length`、残缺报文、非法 JSON 都会
  收到 `-32700` 错误（`id: null`，消息中带有定位信息），服务端不会崩溃，
  并尽力与后续报文重新同步。

## 位置编码策略

- 支持 `utf-16`（默认）、`utf-8`、`utf-32` 三种 position encoding。
- 初始化时读取客户端 `capabilities.general.positionEncodings`，按客户端
  声明的顺序选择第一个受支持的编码，并通过响应中的
  `capabilities.positionEncoding` 告知；客户端未声明时使用 `utf-16`。
- 行列换算按 Unicode 码点行走：代理对（如 emoji）不会被拆分，组合字符
  （如 `é` = `e` + 重音符）按各自编码单元正确计数；落在码点中间的偏移
  会回退到码点边界。跨行编辑与行尾插入均按行起始偏移表定位。

## 配置项

服务端无外部配置文件；全部行为通过 `initialize` 参数协商：

| 配置项 | 位置 | 说明 |
|--------|------|------|
| 位置编码 | `initialize.params.capabilities.general.positionEncodings` | 客户端偏好列表，默认 `utf-16` |

## 测试

```bash
mvn test
```

覆盖：分帧错误与恢复（`MessageIOTest`）、编码边界/代理对/组合字符
（`PositionCodecTest`）、乱序/重叠/重复/回退变更（`DocumentManagerTest`）、
诊断与符号分析（`AnalyzerTest`），以及端到端的生命周期异常、取消竞争、
并发处理、诊断时序与导航（`LanguageServerTest`，通过真实分帧管道驱动服务）。
