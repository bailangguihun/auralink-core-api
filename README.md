# 画音智链 - 后端服务

这是画音智链项目的后端服务，使用SpringBoot实现，提供了用户管理、文件上传和AI生成服务的API。

## 功能特点

- 用户管理系统（注册、登录）
- JWT认证
- 图片上传与处理
- 图像描述生成
- 音乐生成
- 生成日志记录
- 定时清理临时文件
- 兼容原Python Flask后端API

## 技术栈

- Spring Boot 3.2.4
- Spring Security + JWT
- Spring Data JPA
- SQLite数据库
- RESTful API

## 与前端交互

后端服务提供了以下API接口：

### 认证API

- 注册: `POST /api/auth/register`
- 登录: `POST /api/auth/login`

### 健康检查API

- 健康检查: `GET /api/health`
- 旧版健康检查: `GET /health`

### 用户API

- 获取用户信息: `GET /api/user/profile`
- 获取生成历史: `GET /api/user/logs`

### 功能API

- 上传图片: `POST /api/upload-image`
- 生成图像描述: `POST /api/describe-image`
- 生成音乐: `POST /api/generate-music`
- 临时文件清理: `POST /api/cleanup`
- 获取音频文件: `GET /api/audios/{filename}`
- 获取模型列表: `GET /api/models`

- 获取模型列表: `GET /models`
- 生成图像描述: `POST /describe_image`
- 生成音乐: `POST /generate`

### Auralink 2.0 MediaAsset API

- 认证用户上传 JPEG/PNG 图片: `POST /api/v1/assets/uploads`，multipart 字段名为 `file`，可选 `semanticType=IMAGE|PAINTING`
- 获取安全元数据: `GET /api/v1/assets/{assetId}`
- 内联读取内容（支持 HTTP Range）: `GET /api/v1/assets/{assetId}/content`
- 下载内容: `GET /api/v1/assets/{assetId}/download`

公开资源可匿名读取；私有资源仅所属用户可读取。API 只公开 MediaAsset UUID 和逻辑 URL，不返回数据库内部 ID、存储键或服务器绝对路径。旧版上传和文件接口保持原有存储与响应契约，不会双写 MediaAsset。

### Auralink 2.0 官方画作目录 API

- 公开画廊检索与分页: `GET /api/v1/paintings`
- 公开每日推荐: `GET /api/v1/paintings/daily`
- 登录后查看完整官方注释: `GET /api/v1/paintings/{paintingId}`
- 登录后收藏/取消收藏: `PUT|DELETE /api/v1/paintings/{paintingId}/favorite`
- 登录用户的画作收藏: `GET /api/v1/me/favorites/paintings`

画廊只返回 `ACTIVE`、存在图片且标记为可见的官方目录记录；详情仍可读取已知 UUID 对应的无图片官方记录。列表、详情和收藏响应仅使用 Painting/MediaAsset UUID 与逻辑内容 URL，不公开数据库内部 ID、MediaAsset 存储键或服务器路径。用户上传和 AI 生成图片仍然只是 MediaAsset，不会进入官方 Painting 目录。

### Auralink 2.0 AI 画作导览 API

- 读取当前有效缓存（不会触发模型调用）: `GET /api/v1/paintings/{paintingId}/guide`
- 确保标准导览存在（缓存未命中时调用本地 Guide Service）: `POST /api/v1/paintings/{paintingId}/guide`
- 预留语音入口（当前固定返回 501）: `POST /api/v1/paintings/{paintingId}/guide/audio`

三个入口均要求登录。标准导览按 Painting 与确定性来源哈希缓存，对所有用户一致；访问不会写入 `generation_logs`、Creation 或用户浏览历史。POST 缓存未命中受每用户与全局滚动额度以及全局并发闸门保护，GET 与缓存命中不消耗生成额度。Guide Service 是独立的轻量 Python 进程，只监听回环地址，通过 `X-Auralink-Internal-Token` 与 Spring 通信，并从同一个私有 `backend/.env` 读取 Qwen 与内部令牌。默认 `AURALINK_GUIDE_ENABLED=false`，本轮不会调用真实模型。

导览知识文件沿用服务器已部署但不进入新 Git 历史的 `frontend/public/data/poetry-graph.json` 与 `poetry-stats.json`。启用导览前必须在服务器本地确认两者存在且可解析；缺失、越界、符号链接逃逸或格式错误会安全返回 `GUIDE_CONTEXT_INVALID`，不会回退到网络或创建另一套知识库。测试使用仓库内生成的合成夹具；真实继承资源验证仅在这些外部文件已部署时执行。

### Auralink 2.0 私有工作流定义 API

- 读取节点与操作能力目录: `GET /api/v1/workflow/node-types`
- 创建和分页列出当前用户的工作流: `POST|GET /api/v1/me/workflows`
- 读取、完整替换和删除当前用户的工作流: `GET|PUT|DELETE /api/v1/me/workflows/{workflowId}`
- 只验证、不保存工作流: `POST /api/v1/me/workflows/validate`

所有入口均要求登录。工作流只保存用户私有、单源单终点的规范化生成链定义；不保存实际输入，不执行模型，也不写入 Creation、CreationStep 或 `generation_logs`。功能默认由 `AURALINK_WORKFLOWS_ENABLED=false` 关闭，但能力目录在登录后仍可读取并报告关闭状态。完整图协议、操作白名单、提供商代码、终点规则、错误契约和 Round 8/9 边界见 [`docs/round7-workflow-definition.md`](docs/round7-workflow-definition.md)。

### Auralink 2.0 Creation 提供商适配器

Round 8 在内部实现了 `seedream-5`、`qwen3vl-seedream5`、`qwen3-vl-plus` 和 `auralink-vmm` 的有界、强类型适配器；`reserved-video` 仍无适配器。它们只生成经校验的短生命周期文本或暂存制品，不提供公共执行端点，不创建 MediaAsset、Creation、CreationStep，也不写 `generation_logs`。所有适配器默认由 `AURALINK_CREATION_PROVIDERS_ENABLED=false` 关闭；工作流能力 API 的 `executionAvailable` 在 Round 9 前继续为 `false`。配置、安全边界、错误分类、无付费重试规则及 Round 8.1/9 交接见 [`docs/round8-provider-adapters.md`](docs/round8-provider-adapters.md)。

## 配置说明

安全默认值和属性映射位于 `src/main/resources/application.yml`。本地密钥及部署覆盖值只放在未跟踪的 `backend/.env`；可提交的 `backend/.env.example` 只能包含空值或安全示例。

Spring Boot 通过原生 `spring.config.import` 加载扩展名为 `.env` 的 properties 文件，不依赖额外的 dotenv 库：

- 从 `backend/` 启动时，默认加载 `./.env`。
- 从其他工作目录启动时，在启动进程环境中设置 `AURALINK_ENV_FILE`，可使用绝对路径或相对于进程工作目录的路径。
- `AURALINK_ENV_FILE` 用于定位文件，因此必须在启动 Spring 前由 shell、容器或服务管理器提供；不能依赖目标 `.env` 自己定义该变量。

主要运行配置包括：

- `SERVER_PORT`、`AURALINK_DATABASE_URL`、`AURALINK_JPA_DDL_AUTO`、`AURALINK_FLYWAY_ENABLED`
- `AURALINK_JWT_SECRET`、`AURALINK_JWT_EXPIRATION_MS`
- `AURALINK_VMM_URL`、`AURALINK_QWEN_SERVICE_URL`
- `AURALINK_UPLOAD_DIR`、`AURALINK_AUDIO_DIR`
- `AURALINK_MEDIA_ASSET_DIR`、`AURALINK_MEDIA_ASSET_MAX_UPLOAD_BYTES`、`AURALINK_MEDIA_ASSET_MAX_GENERATED_BYTES`
- `AURALINK_MEDIA_ASSET_MAX_IMAGE_PIXELS`、`AURALINK_MEDIA_ASSET_PUBLIC_CACHE_SECONDS`
- `AURALINK_PAINTING_CSV_PATH`、`AURALINK_PAINTING_PICTURE_DIR`、`AURALINK_PAINTING_IMAGE_BASE_URL`
- `AURALINK_PAINTING_CATALOG_IMPORT_ENABLED`、`AURALINK_PAINTING_CATALOG_IMPORT_FAIL_ON_ERROR`、`AURALINK_PAINTING_CATALOG_IMPORT_BATCH_SIZE`
- `AURALINK_PAINTING_DAILY_ZONE`
- `AURALINK_GUIDE_ENABLED`、`GUIDE_AI_SERVICE_URL`、Guide 超时与知识上下文限制
- `AURALINK_GUIDE_INTERNAL_TOKEN`、`QWEN_API_KEY`、`QWEN_BASE_URL`、`QWEN_MODEL`
- `AURALINK_WORKFLOWS_ENABLED`、`AURALINK_WORKFLOW_SCHEMA_VERSION`、工作流图和元数据上限
- `AURALINK_CREATION_PROVIDERS_ENABLED`、`AURALINK_PROVIDER_STAGING_DIR`、提供商输入/输出上限、超时与并发上限
- `SEEDREAM_API_KEY`、`SEEDREAM_BASE_URL`、`SEEDREAM_MODEL`、`PAINTING_MUSIC_SERVICE_URL`、`AURALINK_VMM_OUTPUT_DIR`
- `AURALINK_CORS_ALLOWED_ORIGINS`、HTTP客户端超时以及远程资源抓取限制

所有模型或 AI 提供商密钥仍必须由 `backend/.env` 提供，不得写入前端或提交到 Git。

创建提供商适配器的受控服务器本地验证流程见
[`docs/round8-1-live-provider-validation.md`](docs/round8-1-live-provider-validation.md)。该流程仅用于 ROUND 8.1 审计，不是公开执行接口，也不会写入 Creation、MediaAsset 或 generation_logs。

MediaAsset 使用两类相互隔离的逻辑存储命名空间：`catalog/` 只引用 `AURALINK_PAINTING_PICTURE_DIR` 下的官方目录文件且不复制；`managed/` 保存用户上传及未来生成资源到 `AURALINK_MEDIA_ASSET_DIR`。ROUND 5 的目录导入器通过内部 catalog-reference 注册方法关联官方图片；后续 Seedream/Qwen/VMM 适配器应以受控流调用 generated-asset 注册方法，不能将提供商路径直接暴露或传给公共 API。Creation 及 CreationStep 后续仅通过 MediaAsset UUID/实体关系引用输入、中间和最终资源。

官方画作目录同步由 `AURALINK_PAINTING_CATALOG_IMPORT_ENABLED` 显式控制，源码模板默认关闭；只有已完成服务器本地 Flyway 基线、迁移与首次受控导入的部署才可在私有环境中开启。未变化的 CSV 与图片成员清单会通过目录指纹跳过重复 upsert。同步引用原图片且不复制文件，CSV 中缺图记录会保留但不会进入普通画廊。

目录指纹由 CSV 内容 SHA-256 与按文件名排序的图片名称、大小和修改时间组成，避免每次启动读取全部图片内容。官方图片目录因此被视为受控来源；替换图片内容时必须同步更新大小或修改时间。正常异常后的同步可安全重试；进程被强制终止时可能遗留 `RUNNING` 审计记录，但后续重试仍按稳定 source key 执行幂等 upsert。

## 安装与运行

### 先决条件

- JDK 17+
- SQLite（由项目 JDBC 驱动提供）
- Maven 3.6+

### 构建与运行

1. 使用Maven构建
   ```bash
   mvn clean package
   ```

2. 运行JAR文件
   ```bash
   java -jar target/auralink-backend-0.0.1-SNAPSHOT.jar
   ```

也可以直接使用Maven运行：
```bash
mvn spring-boot:run
```

### 数据库说明

默认配置使用 `ddl-auto=none` 且禁用自动 Flyway，普通应用启动不会隐式修改数据库模式。版本化迁移位于 `src/main/resources/db/migration/`；既有数据库的基线与迁移只能在明确授权的服务器本地维护窗口执行，禁止通过 `baselineOnMigrate` 自动推断。开发和测试必须使用 `/tmp` 下经 Flyway 迁移的隔离 SQLite 数据库，不得对生产数据库运行测试或手工改变表结构。

## 部署说明

### 使用Docker部署（推荐）

1. 构建Docker镜像
   ```bash
   docker build -t auralink-backend .
   ```

2. 运行容器
   ```bash
   docker run -d -p 5000:5000 --name auralink-backend auralink-backend
   ```

### 直接部署

1. 打包应用
   ```bash
   mvn clean package -DskipTests
   ```

2. 将生成的JAR文件和相关配置文件复制到服务器

3. 使用以下命令启动服务
   ```bash
   java -jar auralink-backend-0.0.1-SNAPSHOT.jar
   ```
