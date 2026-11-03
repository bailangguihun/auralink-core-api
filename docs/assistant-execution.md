# Assistant S2：持久执行、私有 AI 与恢复合同

本文描述本地未提交的 S2 候选；不是生产启用说明或真实模型能力证明。S1 见
`assistant-foundation.md`，其既有实现、V014 文件和测试数据库证据保留。S2 仅增加
`db/postgres/migrations/V015__assistant_execution.sql`；不修改已应用迁移校验和，
不改变 Flyway/DDL 设置，不在应用启动时迁移。

## 权威与兼容

Core 从当前平台认证派生 owner；所有会话、run、图片、引用、导出和取消均按 owner
以及会话关联校验。他人对象与不存在均 404。请求键和 UUID 仅作引用，不是授权。
持久化、请求去重、租约、分支上下文、附件和生命周期由 Core 管理。AI 没有数据库
或文件历史副本；仅使用经 Core 授权、限量的文字和图片字节。

`auralink.assistant.enabled` 默认 false；`auralink.assistant.execution.enabled`
默认 false，是第二个显式开关。两个条件同时为 true 才尝试建立执行层。全部关闭时
不依赖 V014/V015、附件目录、AI 配置或内部令牌；保留旧 V001–V013 行为。仅关闭执行
保留已启用 S1 的历史/附件等基础接口，不发送新模型请求。执行配置无效、V015 缺失
或依赖不满足时执行接口 503，既有 Core 其他模块继续运行。S1 基础配置自身依赖
仍遵循 S1 的显式启用合同；不能用“执行关闭”掩盖基础配置错误。

AI 的 `AURALINK_ASSISTANT_CONFIG_FILE`、
`AURALINK_ASSISTANT_INTERNAL_TOKEN_FILE` 独立于 Critic。不存在、畸形、密钥文件缺失
只使 Assistant unavailable，不改变既有 `/health`、Critic configured-provider
或 Guide 配置。没有 PORT/QWEN_MODEL/DASHSCOPE_API_KEY 冲突或备用 provider。

## Core HTTP API

所有地址位于 `/api/v1/assistant`，要求平台 Bearer 会话，私有响应 `Cache-Control:
no-store`。基础会话/图片/导出接口沿用 S1。本轮新增：

| 方法/路径 | 请求/行为 |
|---|---|
| GET `/models` | 脱敏配置模型、声明能力、configSha256；不调用模型，不将配置标签当实测能力 |
| GET `/artworks?query=` | 有界公开作品检索，返回 UUID、当前批次/修订、公开字段及不确定性 |
| POST `/sessions/{id}/runs` | 接受 chat，返回 `{run,replay}`；不是在浏览器连接中执行 |
| GET `/runs/{id}` | 已保存状态/checkpoint/usage/retrieval/attempt；没有调度副作用 |
| GET `/runs/{id}/events?after=` | 认证 SSE；可用 Last-Event-ID，冲突游标 400，超前游标 409 |
| POST `/runs/{id}/cancel` | 先持久取消/提升 fence，再尽力关闭该上游连接；不撤销已可能发生的费用 |
| POST `/runs/{id}/retry` | 显式单独 attempt，需新 requestKey、expectedVersion、acknowledgePossiblePriorCharge=true |
| GET `/runs/{id}/citations`、`/choices` | 所属 run 的已保存引用/合法选择卡 |
| POST `/runs/{id}/choices` | cardId、optionId、branchId、expectedVersion、requestKey、modelKey；文字由已保存卡确定 |
| POST `/sessions/{id}/messages/{message}/revisions` | branchId、expectedVersion、requestKey、text；创建分支及新版本 |
| POST `/sessions/{id}/edited-runs` | 执行已保存的编辑版本，不重写旧历史 |
| POST `/sessions/{id}/retrievals` | 单独有记录的检索，无模型调用；query 为该输入文字，不向 RAG 发送私人图片/全部历史 |
| POST `/sessions/{id}/summaries` | branchId、requestKey；继承该分支最后成功 chat 的模型配置，不能指定替代供应商 |
| GET `/sessions/{id}/summaries?branchId=` | 摘要、对应 run、模型、状态、usage、是否过时 |
| GET `/sessions/{id}/timeline?branchId=&includeHistory=false` | 仅 Assistant 的轮次/结果/分类；默认当前分支 |
| POST `/sessions/{id}/messages/{message}/classification` | requestKey、expectedVersion、label；追加用户分类版本 |

Chat/edited/retrieval 的共享请求形状：

```json
{"branchId":"<UUID>","expectedVersion":0,"requestKey":"<8-128 allowed chars>",
 "text":"<bounded text>","attachmentIds":[],"modelKey":"<explicit registry id>",
 "retrieval":{"artworks":true,"ragflow":false},
 "artworkRefs":[{"artworkId":"<UUID>","expectedBatchId":"<UUID>",
                 "expectedRevision":1,"includeImage":false}]}
```

这里只是协议示例，不含真实身份。独立检索使用相同请求 DTO 的 modelKey 字段以保持
接口验证一致，但 RETRIEVAL run 不调用/选择该模型。owner/clientId、上游地址、文件
路径、凭据和 RAG dataset 不属于浏览器可覆盖配置。

## 受理、队列、租约与恢复

1. 校验身份/归属/分支版本、模型能力、附件 READY/同会话、作品当前修订和请求键。
   持久事务同时写用户输入、请求映射、QUEUED run、分支 head；失败不得发送上游。
2. 原键原载荷重放返回原 run，不调用 provider。原键不同载荷 409；过期/删除请求
   返回 410，不把旧键视作新收费任务。最小请求墓碑保留，不因结果删除重新放行。
3. 每会话至多一个 EXECUTING；有界 QUEUED 与执行槽分开。新 CHAT 会取消过期/排队
   SUMMARY，并优先领取；已执行 SUMMARY 不因排序被取消和重发。每会话仍限制一个
   未完成 CHAT，防止未完成上下文分叉竞争；默认每会话 pending 上限 8、全局 pending 64、每账户 pending 16；全局/账户上限使用独立数据库队列锁，并不把摘要占位当执行槽。
4. 数据库事务领取 + 全局/owner 容量锁 + session 生命周期锁；租约绑定 worker 和
   单调 fence。心跳/检查点/完成都重验 lifecycle、状态、fence、owner、lease。
5. 准备上下文和引用；RAG 调用前单独记录 retrieval intent；发送模型前记录 model
   intent。上游 HTTP 自动重试、重定向关闭。检查点先提交，SSE 才确认内容。
6. dispatch 后进程丢失、EOF 无终态或结果不明变 INTERRUPTED，不自动重发。过期执行
   lease 同样中断；即使发生在 dispatch 前也采取保守中断，显式 retry 才新增 attempt。
   QUEUED 工作可继续领取。不能承诺供应商 exactly-once、零费用或零数据丢失。
7. 断开浏览器只是断开订阅；已受理 run 在执行期限内继续。取消是独立持久操作。
   删除会话立即 tombstone，撤权并擦除相关正文/引用/选择；V015 trigger 扩展执行
   JSON 的擦除。迟到 worker 不能写回或复活内容；旧订阅不发送删除提交后的新内容。

模型上下文沿目标 input 的 parent 链取有界历史；USER ACCEPTED/COMPLETED 保留，
ASSISTANT 只纳入 COMPLETED。失败/取消/中断回答及其他分支不混入。编辑产生新分支，
重试保留原 attempt。发送前重检公开作品投影和私有附件生命周期，禁止静默忽略图片。
原图保留；仅向模型发送去元数据且最长边 2048 的 JPEG/PNG 派生图，记录原图/派生图
SHA-256、尺寸及变换版本。最多 4 张、派生图合计 8,000,000 bytes、完整内部请求
14,000,000 bytes；可配置更低 AI 限制时要共同绑定并验证，不宣称任意配置互相兼容。

## SSE 与慢客户端

返回事件包括 `run.accepted`、`retrieval.status`、`message.delta`、`run.state`；
有序持久 event 带 contractVersion/runId/branchId/sequence，`id` 为 sequence。
查询旧游标超出保留窗口时返回 `message.snapshot`（已有 checkpoint 和当前序号），
不能通过重连重新调度。显式 done 才能完成上游；EOF 不作成功。

AI→Core 私有流的 `heartbeat {kind:"provider_activity"}` 只表示实际观察到有效的
非答案 provider delta；AI 按 `min(1000ms, idleMs/3)` 限速，不因注释、空 delta
或无数据而定时伪造。Core 严格校验该对象，只刷新传输空闲时间，不调用业务回调、
不持久化正文/来源、不广播给浏览器，也不算完成。总执行时限、取消与无终态 EOF
拒绝成功的规则不变。此事件需匹配的新 Core/AI 私有协议一起发布，不能假定旧 Core
支持。新 Core 仍接受旧 AI 原事件集；反向的新 AI 若先发 heartbeat 给旧 Core 会被协议
拒绝。后续协调发布必须保证配对后才放行生成，不能直接沿用 AI 先更新且立即继续生成
的顺序；这里记录兼容边界，不代表执行或批准新的发布操作。

客户端订阅用 Servlet async/nonblocking WriteListener。最多 32 个硬上限，配置默认
8；两个专属 daemon poller；每次只取一个 event，最多缓存一条 1MiB 帧，
不建立无界输出队列。每订阅 2MiB/默认 25 秒后关闭，客户端按 cursor 再连；
字节预算只在完整帧之间截断。客户端不可写时不加载更多正文。由容器线程处理
写就绪回调，每块最多 8192 bytes，且每块都在 session 生命周期锁内重新检查后写入；
删除不能穿过检查/发送之间的间隙。每个完整帧在同一生命周期锁内 flush，避免小于
容器缓冲区的增量一直滞留至完成或超时；之后才推进 cursor。终态帧在后续 ready
确认后才完成 async 响应，避免大 UTF-8 帧与容器输出收尾竞争。超时/网络错误/认证过期只关闭订阅，不
取消 run。已发送至网络的字节无法撤回，此限制不等于允许删除后的新内容广播。
实际 listener 不支持 async 时明确 503，不能降级为无限阻塞流或绕过鉴权。

## 检索、引用、摘要与选择

平台资料使用共享 Gallery 当前修订及 eligibility；字段白名单再按 public 定义投影，
保留 raw/normalized value、valueStatus/assertionStatus。模型默认仅拿公开文字；
图像须显式选中并通过受控媒体及字节校验。平台引用类型 PLATFORM_ARTWORK，保存
作品 UUID、真实批次/修订、投影版本、摘录 SHA 和检索时间。

RAGFlow 由 AI 的服务器 allowlist 决定 dataset，独立 POST retrieval，不部署 demo
cloud-api/SQLite，不提供 SQL 或导入端点。来源状态 DISABLED/NOT_CONFIGURED/
AVAILABLE/EMPTY/FORBIDDEN/TIMEOUT/UNAVAILABLE 保持独立。失败/空结果对应的回答
附明确检索状态说明，不伪称已获该来源支持。引用类型 RAGFLOW_DOCUMENT，保留
真实 dataset/document/chunk、返回的 version（若有）、接收内容 SHA、config hash
及检索时间。上游没有版本时 provenance.upstreamVersionAbsent=true，sourceVersion
标为 `received-content-sha256:...`，只代表收到的摘录，不能冒充上游版本。

联网搜索由 Core 的可选 Brave Search 适配器完成。默认关闭；启用时 API key 只从
服务账户可读的绝对路径文件加载，不进入 Web、数据库、日志或 Git。适配器固定访问
Brave 的 HTTPS Web Search 端点，禁用重试、重定向、Cookie 与代理继承，限制查询、
返回体、结果数量及超时。每条 `WEB_RESULT` 保存标题、安全公开 URL、摘要、检索时间
和接收内容哈希；网页摘要是不可信参考文本，不是指令，也不等同于页面事实已核验。
Core 不抓取结果页面，内网、回环及带凭据 URL 不进入引用。

检索文本是非可信参考资料，不是指令。只有已保存 citation UUID 可形成引用链接；
模型自造 ID 只是未解析文本。未来 Web 仍必须执行这一解析规则，不能把 Markdown
任意地址变成受信来源。私有历史/图片不进入知识库 ingestion/training。

SUMMARY 默认自动关闭；同时需要全局自动开关和会话 opt-in。当前测试默认阈值4个新
完成 chat、空闲30秒、每小时至多3次；均为候选配置，不是批准的生产策略。摘要键
绑定分支 head/context/model config/prompt；失败或不确定状态不自动补发。同一摘要
重复请求返回已有状态；明确 retry 新 attempt 且计入独立 summary 预算/usage。
选择卡仅严格 schema（最多3张，每张2–4项）；畸形内容保留为普通文本，无修复模型
调用。选项提交从已持久文本取值，绑定来源消息/分支及请求幂等。

规则分类/用户分类只是 Assistant 阶段标签，非对实际作画行为的证明。JSON/CSV 导出
连接已保存轮次、run 状态、usage、引用、选择与分类；默认当前分支，显式历史选项
保留分支。CSV 对公式起始字符转义；无准确计费资料时 usage.available=false/null，
不把估算写成账单。新数据保持 S1 的私有生命周期与删除/备份边界。

## 配置与后续工作

Core 专用配置前缀 `auralink.assistant.execution`：enabled、ai-base-url（固定内部
listener）、internal-token-file（0600 的独立令牌文件）、expected-config-sha256
（AI 配置原始 JSON 精确 SHA）、allow-loopback-http（仅隔离测试）、concurrency/
owner-concurrency、global-pending/owner-pending、lease、poll-interval、total-timeout/idle-timeout、context/output/
subscription 上限、retrieval-timeout、automatic-summaries、summary-turn-threshold/summary-idle/
max-summary-per-hour。不使用现有 Critic token/JWT 来替代内部授权。

AI wire 与模型/RAG 的精确配置见兄弟 AI 候选 `docs/assistant-runtime.md`。Core 和 AI
都只做一次显式请求；AI 内存 attempt guard 是补充，进程重启后的持久防重属于 Core。
真实模型/RAG 能力、生产适配和发布均未验证/未执行；本轮只允许 PG + 本地 mock
完整链路。具体执行的测试和收到的请求计数以 S2 阶段证据索引为准，文档本身不是
通过证据。

仍未完成：Web UI/跨设备交互验收、真实 provider/RAG 小预算验收、生产发布合同、
总存储配额、批准的留存/清理策略、WebP。现有清理仅明确隔离/运维
primitive，不新增生产定时删除或备份恢复。Assistant 配置切换/扩展 schema 的未来
发布必须走原 R25/c5ea；本轮不改控制器、不添 supervisor、不改变现有预览。

补充：公开作品 sourceRecordId 为 batch/revision/annotation-file 逻辑标识/recordNumber 的 SHA-256；annotationFileId 只在内部使用，不向浏览器或模型暴露来源文件名/路径。当前分支 JSON/CSV 导出排除已被 retry 分支取代的失败 attempt，includeHistory=true 才包含历史。

S2 JSON/CSV 导出最多 1000 条消息，超出返回 422 ASSISTANT_EXPORT_LIMIT，不静默导出部分执行元数据。认证后的 Assistant JSON 请求在 Jackson 解析前限制 256KiB（含 chunked）；multipart 图片仍走独立的原校验/限额。上下文窗口默认最近 32 条，持久 manifest 记录 historyTruncated/实际 messageIds/撤回来源导致的 omittedReferenceMessages；不是完整历史分页。

Core retrieval-timeout 默认 31 秒（范围 100ms–32s），覆盖 AI RAGFlow 最长 30 秒；模型列表/取消等短请求仍限 8 秒。若显式配置更短的 Core 检索时限，耗尽返回 TIMEOUT 并持久/展示该状态，不误称 UNAVAILABLE，不自动重试；已发 RAG 请求在 AI 自身有界时限内结束，网络断开不保证取消远端计算。该设置不改变模型执行预算。

帧上限按允许 128000 字符的最坏 JSON 转义（约 768000 bytes）留界，不仅按中文字节计算。单连接仍只缓存一帧、8KiB 分块，连接总预算 2MiB；数据库 checkpoint 保持 S1 的 262144 原始 UTF-8 bytes 约束，不修改迁移。超过输出/持久限额会中止该 run，不裁剪后伪称完整成功。


## S3 浏览和断线受理结果恢复（兼容扩展）

S3 未改 V014/V015。保留旧数组/History 接口，Web 使用新增有界分页，避免旧 messages
窗口或 timeline 的 1000 条上限被误当成完整历史。所有分页返回
`{items,nextCursor,hasMore}`；`limit` 默认 50、范围 1–100。`cursor` 不透明、仅含公开
对象标识，不是令牌或授权。每页重验当前账户/会话生命周期；会话删除后旧 cursor
不能继续读取。分页 GET 不 claim、不 dispatch、不重发模型。

| 方法/路径 | 条目与排序 |
|---|---|
| GET `/sessions/page` | Session；按不可变 createdAt/id 新到旧，不因改名或其他设备写入跳过会话 |
| GET `/sessions/{id}/branches/page` | Branch；createdAt/id 旧到新 |
| GET `/sessions/{id}/branches/{branchId}` | 单个所有的分支；用于当前分支版本，不依赖它是否在第一分页 |
| GET `/sessions/{id}/messages/page?branchId=&includeHistory=false` | Message；**先返回最近一页，每页内部旧到新**；nextCursor 取更早一页，Web 应前插。分支 cursor 固定初始 head，新的回答不混入正在翻阅的旧快照；重新不带 cursor 读取最新 head |
| GET `/sessions/{id}/runs/page?branchId=&activeOnly=false` | 公开 Run DTO；createdAt/id 旧到新；branchId 不填表示会话全部分支，activeOnly=true 仅 QUEUED/EXECUTING，便于刷新恢复现存运行 |
| GET `/sessions/{id}/summaries/page?branchId=` | 兼容既有 snake_case 摘要条目，按稳定摘要 UUID 排序；重试指向的新 run 不移动分页边界 |
| GET `/sessions/{id}/timeline/page?branchId=&includeHistory=false` | Timeline；与 messages 页完全相同的旧页 cursor/排序；额外 `classificationVersion` 为当前分类版本，没有分类为 0 |

消息路径的分页查询只读取 limit+1 条消息正文；验证分支路径不使用原有 10000
深度截断。`includeHistory=true` 读取同会话保留的分支版本，以 createdAt/id 做稳定
反向 keyset。记录分页不承诺对整个会话的全局跨请求数据库快照；删除始终立即撤权，
追加内容通过重新读取最新页可见。导出仍是明确有界：S2 执行导出超过 1000 条返回
422 ASSISTANT_EXPORT_LIMIT，不假装导出了全部内容。

`GET /status` 兼容增加 `automaticSummaryAvailable`，以及执行已配置时的
`summaryTurnThreshold`、`summaryIdleSeconds`、`maxSummaryPerHour`；执行关闭时
后三项为 null、automaticSummaryAvailable=false。这是候选运行配置，不是生产预算批准。

`POST /requests/resolve` 是**只读受理结果查找**，不是重试/聊天接口。输入
`{operation,requestKey,sessionId?}`；operation 只接受 CREATE_SESSION、CHAT_EXECUTION、
EDITED_EXECUTION、RETRY_EXECUTION、RETRIEVAL_EXECUTION、SUMMARY_EXECUTION、
REVISE_MESSAGE、UPLOAD_ATTACHMENT、CLASSIFY_MESSAGE。选择卡使用 CHAT_EXECUTION。
返回 `{operation,status:"FOUND"|"NOT_FOUND",sessionId,resultType,resultId}`，
resultType 为 SESSION/RUN/BRANCH/ATTACHMENT/MESSAGE。无需在浏览器持久化正文或图片
来找回丢失的响应。owner 从平台认证取得；提供 sessionId 时必须与所有的请求匹配。
查找序列化于同账户的受理锁；不写请求、不释放键、不返回 payload/body/hash。
已过期或删除的已知请求仍为 410 ASSISTANT_REQUEST_GONE；不同载荷重放仍按原接口
409。NOT_FOUND 只描述查找时的事实，不授权自动创建新的收费任务或换键绕过冲突。

### S3 附件恢复补充（2026-10-14）

附件在待发送阶段通过会话级 `/attachments/draft` 分页恢复，仅在原上传请求期限内、READY且未被任何消息引用时有效。请求状态查询提供 `resultStatus/attachmentLinked/expiresAt`，客户端不得把上传尚未完成的404当作空历史或无限重放同键。发送后仍由消息 attachmentIds 和认证内容接口恢复；完整定义见 `assistant-foundation.md` 的“S3 附件刷新修复”。这些读取不分派模型。

## 人工费用监控的生成暂停（2026-10-14 来源闭合）

首发按既有已认证用户规则开放；费用由运营人工检查，必要时人工暂停。
这不是金额硬上限、参与者 allowlist，也不改变原 S5 的有限测试账本。
RAGFlow 首发保持禁用，平台公开作品目录是独立能力。

受审 Deploy 的既有 `core_publish.py assistant-generation status|suspend|resume`
在 R25 共享锁内操作当前策略绑定的专用附件根，不重启 Core/AI、不修改运行配置。
它维护固定私有文件 `.generation-suspended-v1`，精确 ASCII 内容
`ArtLIVE Assistant generation suspended v1\n`，0600、单一 hardlink、常规文件、
owner 与 0700 专用根一致。控制器写前核对 namespace、身份、事务状态并保存操作记录。
备份包含此状态；恢复不能悄悄删除暂停标志。不要用浏览器接口或随意改配置替代它。

Core 每次新生成准入、领取 QUEUED 工作和潜在付费发送前检查标志及根/file身份。
缺文件表示 AVAILABLE；合法文件表示 ASSISTANT_GENERATION_SUSPENDED；
不可读、内容/权限/owner/链接或根身份不符表示 ASSISTANT_GENERATION_CONTROL_UNAVAILABLE，
只对生成 fail closed。没有配置新路径、短 TTL、后台删除或新迁移；Assistant 完全关闭时
不会读取这个标志，不影响旧 V001–V013 启动兼容。

同一请求的已有结果重放、历史/分页、图片、导出、删除、取消和目录搜索仍可使用。
新聊天、选择生成、编辑后生成、显式重试、全新摘要被拒绝；摘要已有请求/同上下文
去重仍返回原结果。已排队任务保留 QUEUED，恢复后只领取原任务；中断/结果未知任务
继续按原 lease/fence 合同处理，不自动改成 QUEUED、不重发。暂停竞态中已经领取、
但尚未通过发送检查的任务可能变为 FAILED，需要明确重试。

标志不是上游计费的原子开关。已越过发送检查或已经到达供应商的执行可以继续完成并
收费；暂停不自动取消或重新发送它们。操作者应查看仍活动的 run 和供应商账单，
不能把 Core 取消成功、暂停或缺失 usage 解释为费用已停止。

`GET /status` 增加 `generationAvailable` 与 `generationState`。
`executionAvailable` 继续表示历史执行接口可访问，不因人工暂停变 false；Web 应只
禁用付费生成控件，保持历史/图片/取消可用。旧页面未刷新时仍由服务端 503 精确拒绝，
不自动重试。`automaticSummaryAvailable` 暂停期间为 false。
