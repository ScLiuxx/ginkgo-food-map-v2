# 前端架构

## 管理端身份与布局（2026-11-03）

统一使用`/login`和auth模块的LoginForm，经既有`/auth/login`建立会话；旧`/admin/login`仅兼容跳转。login.store.isAdministrator根据服务端身份的`ROLE_ADMIN`计算，管理员优先于普通回跳进入工作台，普通用户保留安全站内回跳。DefaultLayout在身份恢复前隔离内容，管理账号访问前台路由也进入工作台，管理员日常使用普通账号需先退出。

AdminLayout在会话恢复后通过admin模块公开的verifyAdminSession调用`GET /api/admin/me`，验证成功才挂载工作台。后端按当前数据库角色限制`/api/admin/**`，并保留JWT和Redis验证；前端403拒绝访问、503显示重试、401清理会话。管理校验固定绑定请求开始时的凭证，凭证切换或布局卸载后丢弃旧结果。App统一负责恢复与跨标签页凭证同步，不新增第二套登录存储。

AdminReviewPage从auth模块取得真实身份及退出行为，以props/events传入admin模块，admin模块不读取auth内部Store。认证失效时销毁审核组件及其内存状态。后台品牌不链接前台，提供退出以切换普通账号。

useRestaurantRequests读取真实申请分页、详情和私有图片，维护当前申请独立草稿并调用真实通过/驳回接口。ReviewInlineField按字段类型处理文字、数字、枚举和标签，只有维护协议白名单进入通过请求，不提交展示文案和原始申请身份。状态/搜索/翻页清除旧详情，搜索防抖250ms；固定请求凭证，取消与版本号隔离迟到响应。提交期间锁住切换和重复写入，网络失败保留草稿；收到终态回执后读取服务端审核快照并刷新队列，查询失败不能被视为审核失败。连续审核选中刷新后下一条，取消勾选保留回执，队列缩页沿用此策略。图片经Bearer取得Blob，切换/卸载撤销URL，原申请图片不自动变为公开相册。

审核白名单使用独立协议，包含restaurantType、supportsDineIn、supportsDelivery，不再照搬基础直接维护输入而漏掉这三个字段。类型使用普通餐厅/食堂选择；堂食与外卖使用未知/null、支持/true、不支持/false，草稿编辑、提交、服务端快照和公开详情保持一致。

## 正式商户相册

RestaurantDetail使用restaurant模块自己的RestaurantAlbum，不再将ReviewPanel当前点评页的photos事件作为顶部图片源；点评照片保留在点评区。普通餐厅与食堂共用正式相册组件，评分刷新不会重新挂载相册。桌面保留左图右资料，手机统一先资料后相册。

restaurantAlbumApi按20张请求有界游标分页，投影id/position/cover，图片地址由商户ID和正式关联ID生成，不使用外部图片URL。httpClient仅对明确的公开相册GET省略Bearer，管理员原申请图片仍携带认证；避免过期令牌阻止公开图片浏览。

useRestaurantAlbum使用独立代次、AbortController和四并发工作池加载JPEG Blob；封面先加载，点击仍在队列的缩略图会优先处理。分页失败保留已加载内容和当前选择，元数据空态、查询失败和单图失败分别展示；解码错误可单图重试，切店/卸载撤销全部ObjectURL，迟到响应不创建旧图片URL。RestaurantAlbum提供缩略图、大图原生dialog和Esc关闭，不用未审核图、点评图或样例图补空相册。

管理端按publishedPhotos显示本次发布数量和正式链接；已删除目标保留发布记录但不提供公开链接，历史已通过且无正式关联时明确未发布。通过回执按真实发布数量反馈，原photos继续通过管理员私有接口核对。没有增加图片发布写接口或修改后端审核规则。

首页RestaurantCard和地图MapRestaurantPhoto消费目录/详情返回的coverPhoto，停止调用useRestaurantReviewPreview逐商户拉取点评。封面URL按商户和正式关联ID生成，原卡片布局、懒加载、图片失败占位保留；后端分页后仅以当前页ID集合批量查询position=0的有效正式关联，不遍历相册或在目录排序/计数中加载图片。无正式封面保持null，不回退为点评照片。餐厅类型优先使用明确restaurantType，旧数据缺省时才兼容食堂分类。

RestaurantCorrection在已有商户资料加载成功后复用RestaurantAlbum回显公开照片，使用源商户名称，编辑店名不会把旧相册署名改成尚未通过的新名称。相册状态与useDraftPhotos的新增上传草稿分离，不进入dirty基线、五张新增限制、取消清理或提交photoIds；认证变更/切店/提交成功卸载相册时正常撤销Blob。新店投稿不挂载现有相册，读图失败独立重试且不阻止文字提交。

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
