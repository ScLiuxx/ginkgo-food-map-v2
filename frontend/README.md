# Ginkgo 前端

生产前端采用 Vue 3 + TypeScript + Vite，使用 Vue Router 管理页面、Pinia 管理客户端状态、Axios 访问 Java API。生产环境由 Nginx 提供静态文件并将 `/api` 与 `/auth` 转发到 Spring Boot。

## 工程目录

```text
frontend/
├── config/              Vite、TypeScript、ESLint、Prettier 配置
├── deploy/              Docker 与 Nginx 部署文件
├── src/                 Vue 业务源码
├── .dockerignore        Docker 构建排除规则
├── .env.example         本地环境变量示例
├── index.html           Vite HTML 入口
├── package.json         npm 命令和依赖声明
├── package-lock.json    可重复安装的依赖锁文件
├── README.md            前端说明
└── tsconfig.json        TypeScript 项目引用入口
```

`node_modules/` 和 `dist/` 都是本地生成目录，已被 Git 忽略，不属于源码：前者由 `npm install` 生成，后者由 `npm run build` 生成，可以随时重新创建。

## 本地开发

```powershell
npm install
npm run dev
```

Vite 默认监听 `http://localhost:5173`，并把 `/api` 和 `/auth` 代理到 `http://localhost:8080`。如需修改开发代理目标：

```powershell
$env:VITE_DEV_PROXY_TARGET="http://localhost:8081"
npm run dev
```

## 认证页面

- `/login`：用户名密码登录，登录后默认持久保存JWT；同一浏览器重开网站时由服务端核对身份，无“记住我”选项，不保存密码，不自动续期。
- `/register`：校园邮箱发码、注册及跳转登录。
- `/password-reset`：从登录页“忘记密码”进入，申请专用验证码、设置并确认新密码，成功后清除旧凭证并引导重新登录。

同源标签页共享当前登录，退出或切换账号会同步。登录接口有效期和错误处理见 [API文档](../docs/api.md)，基础认证验收已由用户确认通过。

## 餐厅只读预览

- `/`：美食发现首页、原版候选目录、搜索、分类/杯型/视图筛选、排序与分页。
- `/restaurants/:id`：独立餐厅详情，提供待评级、未知评分、旧档位人均估值、原版停业标记及复制/分享；不存在的ID有专门提示。
- `/scoring-guide`：四维评分、五档杯型与待评级、综合分说明；当前规则以[功能说明](../docs/features.md)为准，不提供推荐率。

当前仅使用 `modules/restaurant/model/catalog.preview.ts` 中带来源说明的25家旧版候选，0条评价、无示例评分，不调用尚未实现的餐厅API。筛选与分页仅针对客户端预览集合，不等同于服务端分页。后端接入时应在餐厅模块增加明确的请求/转换层，不能把前端阅读模型直接当成已确定的HTTP协议。

候选营业、地址及消费未经再次核实，页面始终标识预览。真实地图、新增/编辑餐厅、评价与图片仍按[需求文档](../docs/requirements.md)分阶段实施；账号认证继续沿用实际后端接口。

品牌素材沿用原版：`src/shared/assets/ginkgo-cover.jpg` 从原项目 `build/_cover.b64.txt` 直接解码，SHA-256 为 `18ae5776232f9ac247ff4b31efc1aa9de23f5b89e2c20d918711a64a420273f7`，不重绘或重新压缩。页头使用原版品牌杯子路径，业务徽章复用原版 `cupGlyph` 路径与配色，标语由杯型定义集中维护。首页与评分说明共用同一封面文件，测试固定原版杯型、颜色、标语和首页文案。

手机端搜索常驻，其他条件默认折叠，杯型横向滑动且不产生整页横向滚动；分页后聚焦结果栏，浏览器后退恢复阅读位置。当前筛选/页码保留在页面会话内，刷新浏览器后回到默认目录，不将业务条件写入认证存储。

## 验证

```powershell
npm run check
npm run typecheck
npm run lint
npm test
npm run build
```

`npm run check` 会依次执行代码检查、单元测试和生产构建，是提交前的默认验证命令。目录职责和依赖规则见 `docs/architecture/frontend.md`。

## Nginx 部署

后端在本机运行时，可以从项目根目录构建并启动前端容器：

```powershell
docker compose up -d --build frontend
```

打开 `http://localhost`。Nginx 的后端目标通过 `BACKEND_HOST` 和 `BACKEND_PORT` 配置；部署成完整容器网络后，将其改为后端服务名即可。

后续按店铺列表、地图、评价、身份认证和后台管理的顺序重新实现。V2 不包含旧版单文件前端，需要对照时查看独立的原项目目录。
