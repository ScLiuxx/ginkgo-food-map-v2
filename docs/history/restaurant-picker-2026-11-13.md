# 帮我选一家交付验证

日期：2026-11-13。范围：`feature/restaurant-picker`本地实现，未提交、推送或部署。业务规则与设计取舍见[选店计划](../plans/restaurant-picker.md)，接口见[帮我选一家候选查询](../api/discovery.md#picker)。

## 环境与验证方法

Windows本地、Java 21.0.6、MySQL 8.0.34、Vue/Vite；本次没有在项目基线MySQL 8.4上验收。集成测试使用现有基础设施创建并删除随机隔离数据库，执行既有V1至V44迁移，没有增加迁移，也未向业务库写入测试店铺。

后端独立查询直接读MySQL；本地浏览器联调时关闭榜单自动生成和ES查询/同步，不依赖Redis或ES。首次联调时Redis未运行使整体Actuator健康检查返回503，选店和资料接口仍可正常访问；后续已启动Redis，评分扩展后的本地健康检查为200/UP。

## 首次接通验证

| 检查 | 结果与边界 |
| --- | --- |
| 后端`mvn verify -Drestaurant.integration=true` | 共973项，执行611、跳过362，失败/错误0；打包成功。未开启其他外部环境的套件仍跳过，不计入执行数 |
| 前端`npm test` | 89个文件647项通过，包含取消首次抽取后恢复原条件的回归 |
| 类型、生产构建、Lint | 通过；Lint有2条既有测试桩属性默认值警告 |
| 格式与差异 | 修改文件格式检查、`git diff --check`通过；Java控制分支排版调整后额外执行测试编译 |
| 真实浏览器联调 | 本地真实后端：空白条件、首次抽取、换店不重复、回看后前进、详情返回恢复、修改/取消、关闭重置；无页面脚本异常 |
| 布局 | 1440×1040、390×844、320×780、390×480；无横向溢出，窄屏/短屏条件内部滚动且提交入口可见 |
| 浏览器异常场景 | 仅拦截浏览器响应，验证503后重试、耗尽重开、失效历史禁用详情/导航、空结果不放宽、修改保留与关闭重置；未修改数据库 |

后端关键验证包括：预算/距离边界及未知资料、经营状态、普通店与食堂区分、校区/用户中心、非法输入、排除集合、指定复核顺序、资料变更、10005家候选中访问窗口外ID，以及查询过程中并发修改不混用快照。纯策略测试枚举三个候选的随机分支验证均匀性，避免用少量随机样本断言概率。

测试结果不累加此前定向运行；后端全量之后仅补充注释及等价的大括号排版。前端最后一项修复后已重新运行全量测试和构建检查。

## 最低评分扩展验证

同日后续按确认增加第六项条件，接口新增必填`minRating`。复用普通餐厅已有五分制口碑生成列，在完整候选查询中先过滤再抽样；明确分数时要求有评价且原始口碑分达到门槛，不新增列/表或改变首页、地图查询契约。

- 后端相关5类133项全部执行通过，失败/错误/跳过0，`mvn verify`成功打包；未重新执行后端全量。评分新增覆盖3个门槛含边界、3.4995不按显示舍入纳入3.5门槛、缺统计行/零评价、非法/缺失/null选项、复核顺序及降分失效。测试仍使用隔离MySQL库，实际版本8.0.34。
- 前端全量89个文件649项通过；类型/生产构建与Lint通过，仅2条既有警告。新增覆盖五项不足以提交、第六项主动选择不限、评分参数、摘要与修改/取消恢复。
- 浏览器使用真实后端验证四档请求、非法门槛400、六项必选、评分摘要、修改取消、详情返回及新条件重开；复核1440×1040、390×844、320×780、390×480，无脚本异常或横向溢出，窄屏/短屏条件内部滚动且提交按钮可见。
- 本地真实数据当次清水河5公里共1443家，不限评分返回该完整集合，3分/3.5分门槛各仅1家，4分门槛0家。多数暂无评分，属于当前数据特征；没有为演示补造评价，没有据此修改或放宽业务规则。此结果不是固定候选量，也不是性能评测。

扩展证据位于仓库外`D:/eat/.codex-previews/restaurant-picker-rating-20261113/`：`backend-verification.log`、`frontend-test.log`、`frontend-build.log`、`frontend-lint.log`、`browser-rating.cjs/json`、`browser-check.cjs`、`browser-verification.json`及各尺寸截图。与首次验证分开统计，不累加为全量覆盖数。涉及文件职责仍见下表，当前规则和精确协议以现行计划/API为准。

## 本机串行耗时

通过localhost HTTP串行读取，每个校区先预热1次、抽取10次，再复核1批。条件均为5公里，预算/类别/用餐方式不限，每批5家。测量包含HTTP与应用处理，不包含真实互联网和手机网络；没有并发负载、旧实现对照或生产容量结论。

| 中心 | 当次合格数 | 抽取最小/中位/最大 | 单次复核 |
| --- | ---: | --- | ---: |
| 清水河 | 1443 | 41.6 / 44.2 / 61.9 ms | 44.1 ms |
| 沙河 | 8108 | 49.8 / 52.2 / 55.4 ms | 50.2 ms |

实现每次读取完整合格ID，时间与内存均随候选规模增加；这些数字只能说明本机当前数据上的开销。每次请求内部使用一致快照，多次请求之间仍可能发生资料变化，不能把候选数量当作固定长期值。

## 修改文件与职责

以下路径相对仓库根目录；省略的Java包前缀为`backend/src/main/java/com/foodmap/`，前端模块前缀为`frontend/src/modules/restaurant/`。

| 文件 | 用途 |
| --- | --- |
| `restaurant/controller/RestaurantPickerController.java` | 独立公开只读候选入口 |
| `restaurant/dto/RestaurantPickerRequest.java`、`restaurant/vo/RestaurantPickerVo.java` | 条件、规模校验与候选/数量响应 |
| `restaurant/service/RestaurantPickerService.java`、`restaurant/policy/RestaurantPickerPolicy.java` | 条件解析、一致快照、复核及均匀抽样 |
| `restaurant/service/RestaurantReadService.java` | 按抽样顺序批量补充公开资料、标签与封面 |
| `restaurant/mapper/RestaurantCatalogMapper.java`、`backend/src/main/resources/mapper/restaurant/RestaurantCatalogMapper.xml` | 复用硬筛选，读取完整合格ID |
| `security/config/SecurityConfiguration.java` | 仅公开指定方法和路径 |
| `backend/src/test/java/com/foodmap/restaurant/RestaurantPickerPolicyTest.java`、`RestaurantReadApiIntegrationTest.java`（同目录） | 抽样规则与真实MySQL接口/事务验证 |
| `api/restaurantPickerApi.ts`、`model/picker.store.ts` | 请求契约、预取/历史、取消与重试、详情往返会话 |
| `ui/RandomRestaurant.vue`、`ui/random-restaurant.css`、`ui/PickerIcon.vue` | 已确认弹窗、响应式样式与图标 |
| `ui/RestaurantDetail.vue` | 从选店进入时的返回与失败页入口 |
| `frontend/src/shared/api/httpClient.ts` | 公开只读POST不附加过期账号令牌 |
| `model/picker.store.spec.ts`、`ui/RandomRestaurant.spec.ts`、`ui/RestaurantDetail.spec.ts`、`frontend/src/shared/api/httpClient.spec.ts` | 会话、交互、来源返回与认证边界回归 |
| 删除`model/random.logic.ts`、`model/random.logic.spec.ts` | 移除按首页现有列表抽取的旧逻辑 |
| `docs/api.md`、`features.md`、`development.md`、`README.md`、`plans/restaurant-picker.md`（均在docs内）及本报告 | 当前契约、使用说明、进度、导航、规则与证据 |

## 证据与尚未验收

原始证据位于仓库外`D:/eat/.codex-previews/restaurant-picker-implementation-20261113/`：

- `backend-full-verification.log`：后端完整验证；`verify-backend.py`：隔离环境启动包装，不输出凭据。
- `frontend-final-test.log`：最终前端647项；`frontend-final-build.log`、`frontend-final-lint.log`：构建与Lint结果。
- `browser-check.cjs`、`browser-verification.json`、各尺寸form/result截图：真实接口流程与布局。
- `browser-errors.cjs`、`browser-errors.json`及empty/exhausted/invalid-history截图：故障与边界。
- `measure.cjs`、`measurement.json`：测量方法与串行耗时原始摘要。

仍待真实Android/iOS设备验证GPS授权、高德App唤起与操作手感。单元测试中的定位替身和浏览器尺寸模拟不能替代真机验收。界面按标注经营状态筛选，不代表实时营业；预算与距离为标注人均和直线距离。没有扩展推荐权重、个性化或新表结构。
