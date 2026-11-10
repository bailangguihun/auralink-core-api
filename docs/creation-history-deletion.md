# 工坊历史记录删除契约

`DELETE /api/v1/me/creations/{creationId}` 要求当前认证用户拥有该记录。
成功及对本人已删除记录的重复请求返回 204。无效 UUID、不存在或他人记录返回 404。
只接受 `SUCCEEDED`、`PARTIAL_SUCCESS`、`FAILED`，且 claim_token、lease_expires_at 均为空；
其余状态返回 409 / `CREATION_DELETE_NOT_AVAILABLE`。

操作只设置 `creations.history_deleted_at`，不改变状态、重试版本、时间线、执行账本、步骤、媒体或收藏关系。
`GET /api/v1/me/creations` 在数据库层过滤并计数，`GET /api/v1/creations/{id}` 对已删除记录返回 404。
重试入口同样拒绝已删除记录。删除与重试的 SQL 都带原子状态条件：删除先成功则不能重试，
重试先入队则不能删除；不把删除当作取消生成。公共画廊和其他引用的既有访问规则保持。

## 安装与回退

生产使用受控 PostgreSQL 迁移 `db/postgres/migrations/V017__creation_history_deletion.sql`，
必须在新 Core 启动前通过既有迁移程序应用和登记。应用的生产 Flyway 仍关闭，不在启动时隐式改库。
SQLite 开发环境使用 `src/main/resources/db/migration/V6__creation_history_deletion.sql`。
两份迁移只新增 nullable 列及可见历史索引，原记录默认未删除，无数据清除。

顺序是迁移 → Core → Web。Web 需要新的 DELETE 接口。
回退应用时保留新增列和标记；不要删除媒体、回滚整库或清除执行账本。
旧 Core 不认识删除标记，会重新显示这些记录；因此回退旧 Core 会暂时失去隐藏语义。

## 验证入口

`mvn -B -ntp package` 包含 SQLite 的真实 ORM、所有者校验、HTTP 204/404/409、重复删除、
终态过滤、重试与删除互斥、提交后重新读取，以及关联媒体／步骤／执行账本／收藏保留检查。

`tests/run_creation_history_postgres.py` 使用新建的专用 loopback PostgreSQL，应用 V001–V017，
核验新列与索引，再以 PostgreSQL 运行 `CreationHistoryDeletionTest`。结束后停止该专用实例。
CI 已接入此入口；只使用合成账号和记录，不连接生产数据库，不调用模型。
`tests/run_full_application_startup.py` 同步使用 17 个迁移进行完整应用启动检查。
