# 前端架构

## 账号交互（2026-11-01）

注册调用/register-and-login并通过login.store.acceptSession接收正式会话后进入首页；令牌与公开身份沿用现有认证状态，密码不进入Store。请求期间出现更新的登录时不覆盖该新会话。密码重置调用/password-reset，成功清理请求开始时的旧令牌，通过loginPrefill模块内存一次性传递服务端返回的用户名，跳转/login；登录密码为空，不自动发送登录请求、不在URL或持久存储传递用户名/密码。字段规则集中于accountValidation，失焦/提交显示框下提示。

## 技术基线

- Vue 3 单文件组件与 Composition API
- TypeScript 严格模式
- Vite 开发与生产构建
- Vue Router 路由和页面级懒加载
- Pinia 状态管理
- Axios HTTP 客户端
- Vitest 单元测试
- ESLint 与 Prettier 代码规范
- Nginx 静态部署和 `/api` 反向代理

工程级配置集中在 `frontend/config`，容器和 Nginx 文件集中在 `frontend/deploy`。前端根目录只保留 npm、Vite 和 TypeScript 需要直接发现的入口文件。

## 源码分层

```text
src/
├── app/                 应用启动后的顶层装配、布局和路由
├── pages/               路由页面，只编排模块，不实现底层业务
├── modules/             按业务域组织的功能模块
│   └── system/
│       ├── api/         当前模块的后端调用
│       ├── model/       类型、Pinia Store 和业务状态
│       ├── ui/          当前模块专属组件
│       └── index.ts     模块对外公开入口
├── shared/              不含具体业务语义的公共能力
│   ├── api/             HTTP 基础设施与通用协议类型
│   ├── lib/             无副作用工具函数
│   ├── styles/          全局样式和设计变量
│   └── ui/              跨业务复用的展示组件
└── main.ts              唯一浏览器启动入口
```

## 依赖方向

```text
main -> app -> pages -> modules -> shared
```

- 依赖只能向右，禁止反向依赖。
- `shared` 不得引用任何业务模块。
- 页面通过模块的 `index.ts` 使用公开能力，不直接读取模块内部 API 或 Store。
- 一个模块不能直接读取另一个模块的 Store；跨模块协作通过公开函数或页面编排完成。
- API DTO、表单模型和页面展示模型按用途分开，不能直接把后端实体当作任意组件的状态对象。
- 只有确实跨模块复用的代码才能进入 `shared`，不能把它当作杂物目录。

认证模块统一提供登录表单和账号状态。`app` 布局负责将账号状态传入公共导航展示组件，
公共导航通过事件通知布局执行退出或重试，不直接依赖认证模块。
HTTP 基础设施只负责当前标签页的 Bearer 凭证读写和401清理；503保留凭证供重试，
过期旧请求的401不得清除新登录的凭证。密码仅留在表单局部状态，不进入 Pinia 或浏览器存储。

ESLint 已对主要反向依赖进行静态限制。新增 `restaurant`、`review`、`auth` 或 `admin` 功能时，应在 `modules` 下建立独立目录，并保持相同的内部职责划分。
