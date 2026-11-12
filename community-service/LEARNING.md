# 社区图文与作品成长

本轮参考 `Hang-666-star/assistant_community` 的社区流程，保留 ArtLIVE 的 Core 身份、独立 PostgreSQL、原有画廊引用权限和操作回执。此文描述代码能力，不代表生产已经安装。

## 能力与兼容

旧 schema 1 继续提供原有交流、评论、赞和收藏。显式应用 `db/002_learning_community.sql` 后，`/capabilities` 的 `growth/search/notifications/cases` 为 true；另配置 `COMMUNITY_MEDIA_ROOT` 才开放 `uploads`。健康响应仍为 `{status:"UP",contractVersion:1}`。

002 是一次性加法迁移：保留用户映射、旧帖子/评论/反应、画廊 UUID 与幂等回执，旧帖子默认为自由交流；不在进程启动时运行 SQL。生产执行必须使用 Deploy 的精确摘要校验、受管排空与迁移回执流程。数据库角色、媒体目录和运行配置须另行准备，不能只替换 Web 或 JAR。

## API 增量

路径前缀 `/api/v1/community`，每次通过 Core `/api/user/profile` 校验 Bearer 身份。所有写入沿用每账号的 `requestId` 事务回执；上传使用 `x-request-id`。回包不确定时 GET `/operations/{id}`，不得自动重发。

| 方法与路径 | 合同 |
| --- | --- |
| POST `/uploads` | 原始 JPG/PNG/WebP body，Content-Type、x-request-id、URI 编码 x-file-name |
| GET/HEAD `/media/{id}?variant=preview` | 授权流；省略 variant 得到原图；支持 Range |
| GET `/media/{id}/metadata` | 尺寸、格式、摘要及逻辑 URL，不暴露存储键 |
| POST `/posts` | 兼容旧 body；可加 title/kind/creationStage/creationGoal/helpTopics/tags/imageIds |
| GET `/posts` | page/size/view，加 q/tag/kind/workflow/sort；筛选与分页均在数据库执行 |
| PUT `/posts/{id}` | 作者编辑 content/title/tags/creationGoal；图片与历史版本保留 |
| POST `/posts/{id}/comments` | 兼容旧 content；加 parentId/dimension/suggestion/revisionId |
| GET `/comments/{id}` | 当前可读帖子的原点评，支持超出当前评论页的引用 |
| PUT `/comments/{id}/adopt` | 作者采纳/取消采纳 enabled |
| GET `/posts/{id}/growth` | 任务、版本及当前账号可读的案例；source=community-records |
| POST `/posts/{id}/tasks` | 将已采纳建议整理为任务；按原点评去重 |
| PUT `/tasks/{id}` | 作者将 status 设为 todo/done |
| POST `/posts/{id}/revisions` | 作者 imageIds/note/reflection，行锁分配连续 V2+ |
| POST `/posts/{id}/revisions/{revisionId}/review` | 通知实际参与过点评的同伴，不伪造专家身份 |
| PUT `/posts/{id}/workflow` | seeking_feedback/revising/completed |
| POST `/posts/{id}/case` | 需已完成复盘、采纳点评且有修改版本；整理确定性私有草稿 |
| PUT `/cases/{id}` | 作者编辑 title/summary，明确选择 draft/published |
| GET `/cases` | 仅已发布且来源仍有效的案例；q/dimension/page/size |
| GET `/notifications` | 当前账号的实际评论、回复、点赞、采纳与复评通知 |
| PUT `/notifications/{id}/read` | 当前账号标记一条通知已读 |
| GET `/tags` | 有效帖子的前 20 个标签及实际使用数 |

作品求助须有标题和至少一张本人上传的图片；最多 9 张、5 个标签。上传限静态图片 10 MiB/3600 万像素，用 Sharp 0.34.3 实际解码并生成最长边 1600px WebP 预览，原图完整保留。原图、预览存于绝对路径的私有目录，文件不可变，账号总原图额度 512 MiB；当前仅允许两个并行解码任务。暂未实现后台孤儿文件清理，删除帖子不物理删除媒体，避免误删不确定提交的数据。

发布前图片只有上传者能读；发布后登录用户可读，来源删除后他人失去权限。所有响应 private/no-store，不提供静态文件挂载或带 token 的 URL。画廊引用仍由 Core 按当前访问者重新核验，不把画廊图片复制为用户原创。

成长案例取自最多 12 条已采纳点评与实际版本说明，保留原评论 ID；由作者编辑确认后才发布。它不是模型输出，也不是专家认证。取消采纳不会删除已整理任务或已发布历史案例；作者可调整任务或撤回案例。再次整理返回同一案例，不覆盖作者已编辑内容。

助手交接由 Web 通过账号范围内的短期草稿主动操作，使用现有助手会话与附件接口；本服务不调用模型。房间、语音、墨币、自动模型点评和认证专家匹配未开放。

## 验证与制品

`npm test` 覆盖 schema 1 兼容、上传/权限、双账号成长链、案例私有性和源删除后的访问撤销。`npm run test:learning-postgres` 需全新 loopback 数据库 `community_growth_fixture`，验证实际多连接写入、连续版本、去重和连接重建后的持久性；已有 schema 会被拒绝，不删除数据库。

生产包只包含锁定的运行依赖，包含 002 和 learning 模块，省略其他平台可选包及 npm CLI shims。打包时实际执行 Sharp 解码，绑定 Linux 架构/libc/Sharp 版本，Deploy 在启动前核对本机平台。`community-package.yml` 仍只产出制品，不 SSH、不迁移、不启动服务。

匹配 Web 的 `tests/community.browser.mjs` 验证旧 schema；`tests/community-learning.browser.mjs` 验证 schema 2 的正式构建。两者使用合同 Core/PGlite，不能替代真实 Java、原生 PostgreSQL、生产身份或模型验收。
