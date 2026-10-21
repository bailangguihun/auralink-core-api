# ArtLIVE 画智体 · Core API

ArtLIVE 的核心业务 API。该服务负责身份认证、用户资料、官方画作目录、媒体资源、收藏、AI 导览编排以及私有创作工作流，是 Web 前端与内部 AI 服务之间的可信业务边界。

> 基于 Spring Boot 3.2 与 Java 17。默认本地端口为 `5000`，AI 提供商凭据只保存在服务端。

## 核心能力

| 领域 | 能力 | 默认状态 |
| --- | --- | --- |
| 身份与账户 | 注册、登录、JWT 鉴权、用户资料 | 可用 |
| 官方画廊 | 画作检索、每日推荐、详情与收藏 | 可用 |
| MediaAsset | 图片上传、元数据、内容读取与下载 | 可用 |
| AI 导览 | 缓存读取、按需生成与配额保护 | 默认关闭 |
| 私有工作流 | 工作流定义、验证与用户级 CRUD | 默认关闭 |
| Creation 执行 | 有界队列、租约、恢复与提供商适配 | 默认关闭 |
| 兼容接口 | 旧版上传、描述与音乐生成 API | 保留 |

## 技术栈

- Java 17
- Spring Boot 3.2.4
- Spring Security + JWT
- Spring Data JPA
- Flyway
- SQLite JDBC（本地默认配置）
- Maven
- JUnit 5

## 本地运行

### 环境要求

- JDK 17+
- Maven 3.6+
- 可选：Guide、Critic、VMM 或其他内部 AI 服务

### 启动步骤

```bash
git clone https://github.com/bailangguihun/auralink-core-api.git
cd auralink-core-api
cp .env.example .env
```

至少为 `.env` 设置安全的 JWT 密钥：

```dotenv
AURALINK_JWT_SECRET=replace-with-a-long-random-secret
SERVER_PORT=5000
AURALINK_DATABASE_URL=jdbc:sqlite:./auralink.db
```

启动开发服务：

```bash
mvn spring-boot:run
```

服务默认监听 [http://localhost:5000](http://localhost:5000)，可通过 `GET /api/health` 检查基础状态。

当进程不从仓库根目录启动时，应在启动前通过 shell、容器或服务管理器设置 `AURALINK_ENV_FILE`。该变量用于定位环境文件，不能依赖目标 `.env` 自己定义。

## 常用命令

| 命令 | 用途 |
| --- | --- |
| `mvn test` | 运行单元与集成测试 |
| `mvn clean package` | 测试并构建可执行 JAR |
| `mvn clean package -DskipTests` | 跳过测试构建 JAR |
| `mvn spring-boot:run` | 启动本地服务 |
| `java -jar target/auralink-backend-0.0.1-SNAPSHOT.jar` | 运行构建产物 |

## 主要 API

除公开健康检查、认证入口和公开画廊资源外，业务接口通常需要 bearer token。

### 身份、账户与健康检查

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/api/auth/register` | 注册 |
| `POST` | `/api/auth/login` | 登录并获取 token |
| `GET` | `/api/user/profile` | 获取当前用户资料 |
| `GET` | `/api/user/logs` | 获取当前用户生成记录 |
| `GET` | `/api/health` | 健康检查 |

### 官方画作目录

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/api/v1/paintings` | 检索与分页浏览公开画作 |
| `GET` | `/api/v1/paintings/daily` | 获取每日推荐 |
| `GET` | `/api/v1/paintings/{paintingId}` | 获取画作详情 |
| `PUT / DELETE` | `/api/v1/paintings/{paintingId}/favorite` | 收藏或取消收藏 |
| `GET` | `/api/v1/me/favorites/paintings` | 获取当前用户收藏 |

### MediaAsset

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/api/v1/assets/uploads` | 上传 JPEG/PNG；multipart 字段为 `file` |
| `GET` | `/api/v1/assets/{assetId}` | 获取安全元数据 |
| `GET` | `/api/v1/assets/{assetId}/content` | 内联读取内容，支持 HTTP Range |
| `GET` | `/api/v1/assets/{assetId}/download` | 下载资源 |

API 只公开 UUID 与逻辑 URL，不返回数据库内部 ID、存储键或服务器绝对路径。公开资源可匿名读取，私有资源仅所属用户可访问。

### AI 导览与工作流

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET / POST` | `/api/v1/paintings/{paintingId}/guide` | 读取缓存或确保标准导览存在 |
| `GET` | `/api/v1/workflow/node-types` | 获取节点能力目录 |
| `GET / POST` | `/api/v1/me/workflows` | 列出或创建私有工作流 |
| `GET / PUT / DELETE` | `/api/v1/me/workflows/{workflowId}` | 管理指定工作流 |
| `POST` | `/api/v1/me/workflows/validate` | 只验证、不保存工作流 |

完整协议与错误契约见 [工作流定义](docs/round7-workflow-definition.md)、[提供商适配器](docs/round8-provider-adapters.md) 和 [Creation 执行](docs/round9b2-creation-execution.md)。

## 配置

可提交的 [`.env.example`](.env.example) 记录所有配置项与安全默认值。常用配置分为：

- 运行与数据：`SERVER_PORT`、`AURALINK_DATABASE_URL`、Flyway 与 JPA 设置
- 身份安全：`AURALINK_JWT_SECRET`、token 有效期、CORS 白名单
- 文件与目录：上传、音频、MediaAsset、官方画作 CSV 与图片目录
- 内部服务：Guide、Critic、Qwen、VMM 的 URL、超时、令牌与并发限制
- 功能开关：Guide、Critic、工作流、Creation 与提供商适配器
- 提供商凭据：Seedream、Qwen 等服务端密钥

真实 `.env`、JWT 密钥、内部令牌与模型提供商凭据不得提交到 Git，也不得下发给 Web 前端。

## 数据库与资源边界

- 普通启动默认使用 `ddl-auto=none` 且关闭自动 Flyway，不会隐式修改数据库结构。
- 版本化迁移位于 `src/main/resources/db/migration/`。
- 既有数据库的基线与迁移只能在明确授权的维护窗口执行。
- 测试必须使用隔离数据库，不得对生产数据库运行测试。
- `catalog/` 引用受控的官方画作文件；`managed/` 保存用户上传及生成资源，两者互相隔离。
- 官方画作导入默认关闭，只有完成数据库准备与资源核验后才能显式启用。

## 安全原则

- 所有 AI 密钥和内部服务令牌仅存在于服务端。
- Guide 与 Critic 通过回环网络和内部 token 通信。
- 远程资源抓取受大小、超时与重定向数量限制。
- Creation 与 AI 能力采用关闭即安全的默认值，并设置并发、配额及队列上限。
- 用户上传、AI 生成资源与官方 Painting 目录保持独立。

## Docker

```bash
docker build -t auralink-core-api .
docker run --rm -p 5000:5000 --env-file .env auralink-core-api
```

生产发布由 [auralink-deploy](https://github.com/bailangguihun/auralink-deploy) 的受控流程管理，不应以本地 Docker 示例替代生产发布步骤。

## 参与贡献

请阅读 [贡献说明](CONTRIBUTING.md)。提交 Pull Request 前至少运行 `mvn test` 与 `mvn clean package`，并在涉及迁移、资源目录或 AI 提供商时说明安全边界与回滚方式。
