# 原版国画社区 2.0 的 PostgreSQL 移植

本轮把用户提供的本地 `国画社区系统2.0/国画社区系统2.0` 中 `server.js`、`room-feature.js` 的帖子与房间业务移入现有独立 Community Node 服务。页面与交互直接复用原版，数据库和身份接入 ArtLIVE。本文描述实现及验收边界，不代表生产已迁移或激活。

原版七个核心文件的 SHA-256 清单保存在匹配 Web 的 `docs/community-v2-reference.md`，并与仓库外阶段 `BASELINE.json` 一致。后端来源分别为：

| 来源 | SHA-256 | 移植位置 |
| --- | --- | --- |
| `server.js` | `8ff41c583e8edcaa0c2620c5bf9b2e3c3d4514aab4003bfb48aa446dabbc3164` | `src/v2/posts.mjs` |
| `room-feature.js` | `167a7bdc373297c128b3e3217cf9d4f6c6121f5df5defc54d2ddc78ba8613604` | `src/v2/rooms.mjs` |

源目录和数据保持，未运行原版启动建库或演示数据脚本。原版 SQLite、账号、密码、默认管理员、AI 示例帖子和演示互动不导入 ArtLIVE。统一身份、媒体权限漏检修正和异步事务适配属于集成与安全修正，不改变原版布局目标。

## 组件与身份

| 文件 | 职责 |
| --- | --- |
| [gateway.mjs](../src/v2/gateway.mjs) | 平台身份、媒体会话、单次 WS ticket、请求回执、来源与媒体所有权检查 |
| [database.mjs](../src/v2/database.mjs) | 有限 SQL 方言适配、参数绑定、异步事务上下文与写入顺序 |
| [tables.mjs](../src/v2/tables.mjs) | 审查过的表名映射到 `community.v2_*` |
| [posts.mjs](../src/v2/posts.mjs) | 帖子、引用、评论、搜索、标签、通知、墨币、房间 AI 点评与管理 |
| [rooms.mjs](../src/v2/rooms.mjs) | 原版房间、作品、标注、绘制、成员、门票、共享及语音信令 |

Core 新增认证接口 `GET /api/user/community-identity`，返回平台 ID、账号名、展示名、邮箱和实际 authorities。Community 通过此接口核实 bearer token，管理员入口只认 Core 的 `ROLE_ADMIN`，不接受浏览器指定角色。不另发 JWT，不复制平台签名密钥，不保存社区密码。

`v2_users.id` 使用已有平台数字 ID；用户名以 Core 为准。社区昵称、头像、邮箱为社区展示资料，不构成第二套登录。专家资格为社区管理记录，不等同于平台管理员角色。原始 003 迁移包含标明 AI 的历史文化智能体身份和诗人表。AI 诗人功能现已移除，历史表和数据保留，既有迁移及其摘要不改写；这些历史记录不赋予真人登录或认证专家资格。

## 显式 003 迁移和兼容投影

[003_reference_community.sql](../db/003_reference_community.sql) 是继 001/002 之后的显式迁移，运行进程不执行 DDL。`CommunityStore.ready()` 接受 `[1]`、`[1,2]`、`[1,2,3]`；只有 schema 3 加上私有媒体目录才创建新 gateway。健康合同仍为 `{status:"UP",contractVersion:1}`，旧 API 继续存在。

本轮最终 003 SHA-256 为 `f6dc7b8b7abfcb872f38fd396abb860e4c6e90c9e44e761dee190baa92b88c54`，与 Deploy `REFERENCE_SHA` 一致。`reference_schema_sql` 只渲染固定迁移及权限/回执 SQL，不连接生产数据库；当前仍没有绑定已安装生产链的 schema 3 有限迁移执行器。

迁移在同一 `community` schema 内建立 `v2_*` 表，不创建另一套生产数据库。原 UUID 帖子、评论、画廊引用、成长任务、版本、案例及旧操作回执继续保留。原帖子和评论映射到 `legacy_id`；原版界面所需的自增数字 ID 是兼容投影，不替换原 UUID，也不表示导入了原版 SQLite 的历史 ID。

触发器同步基础帖子正文/状态、标签、评论、赞和收藏；评论从原版删除后在 canonical 记录上标记 `reference_deleted`，保留成长引用。原上传图片通过授权的 `legacy-media` 路径显示，不复制旧媒体字节。匹配 Web 已接入旧帖链接、画廊来源、账号草稿、助手手动交接和成长对话框；最终用户流程状态以其浏览器验收记录为准。

原版新增音视频、引用快照、墨币、房间、批注、绘制和 AI 草稿保存在各自的新表中。兼容投影不是两个 UI 的逐字段完全等价映射；旧接口是否展示新媒体、旧成长入口及助手草稿的用户流程需匹配 Web 单独验收。

迁移会放宽仅文字帖的正文约束以支持纯媒体帖，并显式区分平台与 AI 展示身份。正式应用须核对最终迁移摘要、当前 schema、运行角色最小权限、前后记录和迁移回执。执行和恢复由匹配 Deploy 有限 successor 绑定，不能照搬原版“启动即建库”的行为，也不能把本地 SQL 执行当成生产迁移证明。

## HTTP 与实时合同

Node 网关前缀为 `/api/v2/community`，表中的原版路径附在该前缀之后。匹配 Web 使用 `/api/community/v2` 作为浏览器 BFF 前缀；Java 的平台接口仍独立存在。

| 原版路径 | 能力 |
| --- | --- |
| `/api/user/profile` | 社区展示资料读取、修改；认证仍由 Core 提供 |
| `/api/uploads?type=image\|audio\|video` | 图片 10 MiB、音频 30 MiB、视频 100 MiB 的原始 body 上传 |
| `/api/posts`、`/api/posts/{id}` | 发布、检索、分页、编辑/删除，以及赞、收藏、评论、引用和投币子接口 |
| `/api/comments/{id}` | 评论删除及点赞；帖子评论与房间批注分离 |
| `/api/search`、`/api/tags`、`/api/notifications` | 综合搜索、标签、实际通知 |
| `/api/coins/wallet\|ledger\|tasks\|room-income` | 钱包、流水、任务、房间收入及领取 |
| `/api/v2/rooms`、`/api/v2/room-invitations/{code}/join` | 房间创建/公开列表、资料、加入/快照、邀请码、成员与生命周期管理 |
| `/api/v2/artwork-assets` | 本地房间作品上传，原图/展示图/预览/缩略图；上限 30 MiB、最长边 20000 像素 |
| `/api/v2/rooms/{roomId}/artwork-sessions` | 当前及历史作品、标注、绘制、AI 草稿 |
| `/api/v2/annotations/{id}`、`/api/v2/drawings/{id}` | 版本/权限检查、隐藏、恢复和删除 |
| `/api/v2/rooms/{id}/messages`、`/share` | 聊天游标历史、共享申请/批准/停止 |
| `/api/admin/*`（不含 `/api/admin/ai/*`） | 内容管理、认证专家及退款能力 |
| `/api/ai/*`、`/api/admin/ai/*` | AI 诗人及其运营已移除，全部返回 404 |
| `/api/media-session`、`/api/ws-ticket`、`/api/operations/{requestId}` | 媒体会话、实时连接凭证及账号范围内的操作核对 |

帖子编辑 PUT 是后端能力；原版 reference 页面没有帖子编辑按钮。匹配 Web 的作品编辑通过保留的成长对话框提供，不能把它表述为原版页面自带的编辑入口。

HTTP 验证实际平台身份和来源。媒体会话 cookie 仅包含不透明随机值，20 分钟有效，读取资源时重新验证账号和权限；不是把上传目录公开挂载。WS ticket 为 30 秒、一次使用，绑定原始身份及准确 Web origin。浏览器同源 `/api/community-ws` 升级至 Node `/ws`，每个信令重新检查当前身份和成员权限。

原版房间三级角色、禁言/移除、历史作品可见性、归一化标注、共享视角/绘制、最多六人开麦及权限失效停止语音均有对应服务端逻辑。麦克风音频走浏览器 WebRTC，WS 传输权限和信令，不在 Node 中录制或转发音频。真实跨网络语音、TURN 和大规模房间能力仍需实际环境验证。

## 事务、回执及读取副作用

`referenceDatabase.unit()` 使用现有 PostgreSQL 连接和事务上下文；普通命令通过事务 advisory lock 保持原版 SQLite 的单写顺序。适配器把审查过的表名、占位符和时间表达式转换为 PostgreSQL，不接受应用启动 DDL。模型网络请求及文件上传采用各自的事务边界，文件与数据库不能被当成一个原子资源。

所有普通写命令需要 `x-request-id`，在 `v2_operations` 中记录账号、请求指纹及 `RUNNING/COMMITTED/FAILED`。相同 ID 的已完成非上传命令返回原结果，改 payload 或上传重放被拒绝；客户端结果不确定时只 GET 核对。它与旧 `community.operations` 同时保留，不能混用状态语义或把 `RUNNING/UNKNOWN` 当作未提交。

钱包/任务读取会按原规则懒建钱包和初始墨币；钱包与房间收入读取可能结算已满足条件的门票。这些 GET 不是数据库只读。初始赠币不能付房费；门票为 1～5 枚，七天待结算和退款/专家欠款有原版逻辑。接口夹具覆盖部分规则，不构成现实货币交易或正式运营验收。

## AI 与外部资料

AI 诗人聊天、本地诗人回复、主题/人格运营与诗词发布路由已移除，网关拒绝整个 `/api/ai` 和 `/api/admin/ai` 命名空间，不调用提供商或写入操作回执。capabilities 的 `ai` 为 false，`aiCritique` 表示房间辅助点评可用。

保留显式同意后的整图/局部图点评及可编辑草稿。房间 BYOK 从 `x-deepseek-key`、`x-deepseek-model` 读取，Key 不写入草稿或操作回执；原版模型请求与有限重试逻辑保留。

源码可读取私有 `COMMUNITY_DEEPSEEK_API_KEY`，但当前 Deploy 生命周期只允许既有 Community 配置及媒体根；它没有因本轮代码自动获得服务端提供商配置。生产如需该配置，必须纳入受管合同，不可绕过原环境隔离。房间图片传给提供商必须明确 `consent:true`；联网知识检索与可替换 provider 只在模拟接口测试中验证，真实付费模型未验。

## 可重复验证与已知缺口

```bash
cd community-service
npm ci --ignore-scripts
npm test
# 只运行本轮 reference 合同：
node --test tests/reference.test.mjs
```

`reference.test.mjs` 默认使用 PGlite、Core 身份 fixture、实际 HTTP/WS 和模拟 provider，覆盖媒体帖与 UUID 投影、引用/评论、墨币/门票/结算、房间 ACL、批注冲突、AI 同意和语音信令。后续安全回归另覆盖资产归属/历史权限、被动 socket 的失效身份、操作结果、canonical 隐藏一致性及输入类型；本轮最终完整测试数量和结果以仓库外阶段 `community-all-final.log` 为准，保留早期失败记录。

同一阶段 `native-reference-test.log` 的七组合同已在隔离的原生 PostgreSQL 最小 runtime 角色下通过，`NATIVE_RESULT.json` 记录测试退出 0、数据保留、未访问原副本且无真实 provider 调用。实际 Core 的 `CommunityIdentityBoundaryTest` 八项通过，包含账号实际角色变化时不沿用旧 token/用户名权限的验证。它们不等于所有原版分支、生产数据库或真实模型已经验收。

包夹具已验证确定性重建、真实 native 解码和 13 项负向检查；最终文件数、字节数、制品摘要和平台信息以 `PACKAGE_FIXTURE_RESULT.json` 对应源码快照为准。该包是本地合同夹具，provenance 不代表正式 release，不能用于生产发布。

本地真实副本独立克隆上的实际 Core 登录、双账号发帖/收藏/评论、建房/入房/升级成员/聊天，以及同源 WS、虚拟麦克风和 390px 布局另有浏览器证据。阶段结果明确真实远端语音未验，付费模型未调用；真实数据、凭证和截图仅在仓库外保存。

房间导航、资产 ACL、回执和旧功能交接的代码修正已接入；最终安全测试和浏览器结果按本轮验收记录收口。正式生产仍需当前安装链的有限 successor、受信 release 制品、当前数据保留基线、正式迁移回执及切换后业务验收。已有本地原生测试、SQL renderer 和包校验不能替代这些生产前置条件，不能报告“全部验收”或“已部署”。
