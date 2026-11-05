# Ginkgo 美食地图 V2

V2 是对旧版 Ginkgo 美食地图的渐进式重构。第一阶段采用模块化单体：业务代码按领域分开，运行时仍是一个 Spring Boot 进程，数据使用 MySQL，缓存与会话基础设施使用 Redis。

独立仓库：[kanno111/ginkgo-food-map-v2](https://github.com/kanno111/ginkgo-food-map-v2)。默认分支为 `develop`，历史从原 develop 的 V2 重构基线开始保留。推送 develop 后由 GitHub 检查构建，服务器自托管 Runner 主动下载已检查产物并在本地发布，无需对 GitHub 开放入站 SSH。结果见[GitHub Actions](https://github.com/kanno111/ginkgo-food-map-v2/actions)。

## 当前阶段

当前功能与未完成范围统一见[功能说明](docs/features.md)，接口与调用规则见[API文档](docs/api.md)。ES搜索的实现进度见[搜索计划](docs/plans/elasticsearch-search.md)，当前版本准确度与接口耗时见[最终评测](docs/history/elasticsearch-final-benchmark-2026-11-05.md)。线上可用能力以实际部署版本为准。

## 项目文档

- [文档导航](docs/README.md)：按当前规则、设计、计划、运维和历史分类阅读。
- [功能说明](docs/features.md)：当前能用什么、如何使用、哪些尚未接通。
- [接口文档](docs/api.md)：已实现接口、认证要求及请求响应规则。


## 目录

```text
ginkgo-food-map-v2/
├── backend/                 Spring Boot 模块化单体
├── frontend/                Vue 3 前端与 Nginx 部署配置
├── deploy/                  服务器发布、健康检查、systemd 与 Nginx 配置
├── .github/workflows/       develop 自动检查与发布流水线
├── docs/                    产品、架构和迁移文档
├── compose.yaml             本地 MySQL 与 Redis
└── AGENTS.md                AI 和开发协作规则
```

## 本地启动

前置条件：Java 21、Maven 3.9，以及已经运行的 MySQL 8 和 Redis；也可以使用 Docker Compose 启动它们。
本地数据库、Redis 和 SMTP 凭据写入已被 Git 忽略的
`backend/config/application-local.yml`，文件结构参考同目录的
`application-local.example.yml`。启动后端时必须显式启用 `local` Profile。

```powershell
docker compose up -d mysql redis
cd backend
# 首次启动前生成本机 RSA 密钥；已有密钥时不要重复执行，工具会拒绝覆盖。
java tools/GenerateJwtKeys.java
mvn spring-boot:run "-Dspring-boot.run.profiles=local"
```

前端构建与预览：

```powershell
cd frontend
npm install
npm run build
npm test
npm run dev
```

前端开发地址为 `http://localhost:5173`，请求通过 Vite 代理访问 Java 后端。生产构建由 Nginx 托管，`/api` 请求由 Nginx 反向代理到 Spring Boot。

需要本地餐厅联调数据时，使用 [开发数据工具](backend/tools/seed_restaurants.py)，在Python虚拟环境安装 `backend/tools/requirements-data.txt` 后，从仓库根目录执行：

```powershell
python backend/tools/seed_restaurants.py          # 只预览数量
python backend/tools/seed_restaurants.py --apply  # 明确写入本地开发库
```

工具只读取被忽略的local配置，只接受本机 `ginkgo_food_map`；整批事务写入，跳过已有同名店（包含已删除店），不覆盖或恢复已有资料。模拟数据不是生产种子或正式历史迁移，不创建用户、管理员、评价或评分。旧坐标不作为已确认的GCJ-02位置，统一待定位；普通用户不能认领未知归属的导入店。

健康检查：

```text
GET http://localhost:8080/actuator/health
GET http://localhost:8080/api/public/status
```

邮件健康检查会连接 SMTP、完成 TLS 和账号认证，但不发送邮件，不能据此认定验证码已经投递到收件箱。连接故障可能使 `/actuator/health` 变慢或返回503，而公开状态和餐厅查询仍可正常响应。

本地配置示例通过 `mail.smtp.localhost` 显式指定 EHLO/HELO 客户端名称，避免 Java 自动获取本机规范主机名时等待反向 DNS。已有 `application-local.yml` 需要自行同步该非敏感属性；它不是远端 SMTP 地址，不应修改 `spring.mail.host`。本机 SMTP 认证已复测通过，历史 EOF 尚未复现；排查记录见[开发日志](docs/history/development-02.md#2026-10-28smtp健康检查排查与本地dns延迟修正)。不要为消除告警关闭邮件健康检查，也不要用放宽证书校验或关闭 TLS 替代排查。

运行测试：

```powershell
cd backend
mvn test
```

## 配置

开发环境默认连接：

```text
jdbc:mysql://localhost:3306/ginkgo_food_map
redis://localhost:6379
```

MySQL 默认用户名为 `root`、默认密码为空。实际用户名、密码和端口都应通过环境变量覆盖，具体见 `backend/src/main/resources/application.yml`。Redis 默认无密码，也可以通过环境变量设置。

环境变量示例见 `.env.example`。不要把真实密码提交到 Git。

JWT 使用 `backend/secrets/jwt-private.pem`（PKCS#8 私钥）和
`backend/secrets/jwt-public.pem`（X.509 公钥），该目录已被 Git 忽略，密钥不会打包进 JAR。
从 `backend` 目录启动时默认使用这两个文件；生产环境应通过
`JWT_PRIVATE_KEY_LOCATION`、`JWT_PUBLIC_KEY_LOCATION` 指定部署环境管理的匹配 RSA 密钥。
应用启动时会校验密钥至少2048位且公私钥匹配，缺失或不匹配时拒绝启动。

主要认证入口：`POST /auth/register`、`POST /auth/login`、`GET /auth/me`、`POST /auth/logout`、`POST /auth/password-reset`；验证码申请路径与完整协议见接口文档。
登录以 JSON 提交 `username`、`password`，成功后用
`Authorization: Bearer <accessToken>` 访问需要认证的接口。响应中的 `expiresAt` 是本次会话截止时间。
当前没有刷新令牌，过期后需要重新登录。不要在日志中记录登录请求或响应中的敏感字段。

## 重构原则

1. 旧系统保持可运行，V2 在新目录独立开发。
2. 先建立数据库、身份和模块边界，再迁移页面功能。
3. 每次迁移一个可测试的垂直功能。
4. 跨模块只能调用对方公开接口，不能直接访问对方 Mapper。
5. 第一阶段只部署一个进程；确有扩容需求时再拆微服务。
