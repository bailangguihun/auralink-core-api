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
- MySQL数据库
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

## 配置说明

主要配置位于`application.yml`文件中，包括：

- 数据库配置
- JWT配置
- 文件存储配置
- 模型服务配置

## 安装与运行

### 先决条件

- JDK 17+
- MySQL 8.0+
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

### 数据库初始化

应用首次启动时会自动创建必要的数据库表，但需要先创建数据库：

```sql
CREATE DATABASE auralink CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

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
