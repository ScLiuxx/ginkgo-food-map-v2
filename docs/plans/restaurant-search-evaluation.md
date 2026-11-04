# 真实商户搜索与IK词库评测

评测日期：2026-11-04。结论：保留IK；已按用户授权在本地ES启用仅含**冒菜、螺蛳粉、天街**的小型业务扩展词表，并完成真实ES索引重建对照。不引入大型第三方词库或全部店名。网站搜索入口仍使用MySQL。

## 扩展词典已启用：真实ES重建对照

新增[扩展词表](../../deploy/elasticsearch/ik/ginkgo-food.dic)和[IK配置](../../deploy/elasticsearch/ik/IKAnalyzer.cfg.xml)，在本机已有的D:/healthy-agent/compose.yml中只读挂载。现有IK插件和基础词典已迁入外部持久卷，原ES数据卷保留；重建服务后集群green。具体挂载、后续加词与恢复方法见[词典维护说明](../../deploy/elasticsearch/README.md)。

加载词典后，用同一44条查询、同一9562家公开商户重新建立随机实验索引并执行真实ES搜索。公开检索快照和冻结相关集的SHA-256均与原评测一致，查询DSL和权重未修改，因此以下是相同数据上的实际搜索对照，已超出此前独立JVM的词项诊断。

| 查询 | 原词典预期目标命中 | 三词扩展后实际命中 |
| --- | --- | --- |
| 冒菜 | 179/192 | 192/192 |
| 螺蛳粉 | 31/32 | 32/32 |
| 龙湖天街蜜雪冰城 | 3/4 | 4/4 |

上述统计使用smart_and策略的完整命中ID。44条查询中未发现预期目标总覆盖或前5条目标数量下降；完整店名目标仍8/8排第一，普通词法Hit@5仍35/35。宏平均全量目标覆盖从96.47%提升至97.47%（不等于Recall@5）。单字“茶”仍覆盖410/1049个名称目标，没有出现此前批量加品牌整词导致的26家损失。

8条查询的前5顺序发生变化，完整结果中的其他顺序也可能变化，因此不能宣称“排名完全不变”或所有相关性都无回退。目标集仍是既有字段规则，不是完整人工语义标注，尚需新的用户查询验收。简称、拼音、错字和单字子串限制仍在。

真实ES字段语义/初始化安全测试11项、评测工具测试6项全部通过。两个随机实验索引均已清理；现有业务/练习索引文档数与别名定义在服务重建前后一致，正式restaurant-search仍指向v002、文档数0。没有重建其他应用的已有索引，也没有替换网站搜索链路。

此次实际搜索原始结果在`D:/eat/.codex-backups/search-evaluation-20261104-with-dictionary/`；备份、全部44条的前后目标计数和前5条结果在`D:/eat/.codex-backups/ik-install-20261104/`（comparison.json）。下方历史表格和逐条展开仍保留**原词典基线**，避免覆盖对照依据。

## 数据和方法

- MySQL只读一致性快照中有9562家公开商户；31家缺地址，仅2家有有效综合评分，商户标签为0条。因此本次不评价标签召回、复杂评分排序或真实用户语义搜索。
- 实测本地MySQL版本为8.0.34，ES为9.5.4，IK安装版本为9.5.4。项目目标基线MySQL 8.4与本次实际运行版本不同，本报告不将测试环境描述为8.4。
- 44条查询在读取ES结果前冻结，包含完整店名8条、店名片段6条、品牌8条、品类5条、单字2条、地址1条、组合6条、简称/类型称呼2条、拼音2条、错别字2条、无结果2条。用例文件见[固定查询集](../../backend/tools/search-evaluation-cases.json)。
- 相关目标按事先固定的名称、分类、地址规则或指定ID生成。它是可复查的词法目标集，不是用户研究或完整人工语义标注。没有列入目标的返回项不一定无关；例如仅按店名标注的品牌集不覆盖地址里提及品牌的其他商户。
- 用例预先分成33条dev和11条holdout，但本轮统一查看并诊断了全部结果，不能把holdout称为未见过的独立验收集。补词方案仍需另一组新查询验证。
- MySQL按现行目录SQL做完整字符串LIKE检索，名称、地址、分类、标签取OR；无其他筛选，按综合分降序、空值末尾、名称和ID排序。SQL基线与导出商户在同一REPEATABLE READ只读事务中读取；不是模拟LIKE，也不是对ES当前页再做MySQL过滤。
- ES复用v002定义，在本次随机物理索引导入检索实验投影，不继承任何正式别名；不实现正式projectionVersion或相册投影，不把此实验工具当业务导入器。三种查询共用名称6、分类3、标签2、地址1的字段权重、完整店名100倍提升和名称短语12倍提升。这些权重仅为实验参数。
- 三种策略分别为`smart_or`（ik_smart、词项OR）、`smart_and`（ik_smart、cross_fields词项AND）、`max_and`（ik_max_word、cross_fields词项AND）。AND可以由名称和地址共同满足，不要求全部词出现在同一个字段。公开候选均显式过滤deleted=false。
- 本次取全量命中ID计算目标覆盖，并单独查看前5条；没有提高ES默认10000窗口，没有把前5条结果当作完整召回。三个策略均检查查询超时、分片失败、总数是否精确及是否完整取回。

## 实际搜索结果

指标Hit@5表示“前5条中至少出现一个预期目标”。普通词法查询有35条非空目标：完整店名、片段、品牌、品类、单字、地址和5条有目标的组合查询；不把简称、拼音、错字算成普通分词能力。

| 范围/指标 | MySQL | ES smart_or | ES smart_and | ES max_and |
| --- | --- | --- | --- | --- |
| 普通词法查询Hit@5 | 30/35 | 34/35 | 35/35 | 35/35 |
| 完整店名目标排第一 | 7/8 | 8/8 | 8/8 | 8/8 |
| 品牌目标排第一 | 4/8 | 8/8 | 8/8 | 8/8 |
| 有目标的组合查询Hit@5 | 0/5 | 4/5 | 5/5 | 5/5 |
| 两条无结果查询保持空结果 | 2/2 | 0/2 | 2/2 | 2/2 |

smart_and与max_and在这44条查询上的命中集合和前5顺序相同；没有证据支持把查询分析器整体切换为ik_max_word。普通词法查询的宏平均全量目标覆盖为MySQL 85.71%、smart_or 97.47%、两种AND均96.47%。这不是Recall@5，也不意味着OR更好：OR虽然多召回目标，同时引入大量不满足全部搜索词的候选。

典型现象：

- `清水河咖啡`：SQL为0；smart_and返回4家字段支持的目标，包括清水河校区库迪、瑞幸；`合信路兰州牛肉面`：SQL为0，ES返回2家目标。这是分词后跨字段组合的实际收益。
- `建设路螺蛳粉`：SQL为0；smart_and将唯一预期目标排第一，smart_or把它排在第11位。这说明组合条件比盲目放宽OR更重要。
- 完整店名`西北餐厅(电子科技大学清水河校区店)`：smart_or返回6210家，smart_and仅1家；不存在的星际拉面站查询，OR仍返回1277家。不能仅看首条命中就忽略其余结果噪声。
- `清水河火锅`：现有名称/地址/分类文字中没有同时满足“清水河”和“火锅”的商户。AND为0是本次字段规则下的合理结果，不代表清水河周围没有火锅店；按地理位置搜附近火锅应使用坐标范围/距离筛选。
- 单字`面`：名称包含该字的1026家中，AND只覆盖741家；`茶`：1049家中覆盖410家。IK按词检索不保证单字子串等价。若产品要求单字包含即命中，需要另行设计短词策略或片段字段，不能把这类差异全靠扩展词典解决。
- `银桦食堂`没有命中名称为“银桦餐厅”的目标；`兰牛`没有命中本用例假定的兰州牛肉面目标。这些是类型称呼/别名探针，尚未确认可以自动做同义扩展，尤其不能把“食堂”和所有普通“餐厅”全局等同。
- `yin hua`、`mxbc`没有命中中文目标，错字`银华餐厅`和`蜜雪冰成`也未被AND纠正。拼音、简称和错字需要独立方案，扩展词表本身不能建立这些对应关系。

## 词典问题与隔离补词实验

内置词典已识别“银桦”“清水河”“火锅”“咖啡”“寿司”。但若干真实业务词被拆开，且在更长店名中切分边界不同：

| 查询 | 查询ik_smart | 漏召回店名及索引ik_max_word关键词 | 当前目标覆盖 |
| --- | --- | --- | --- |
| 冒菜 | 冒 / 菜 | 沸腾三江冒菜馆：冒 / 菜馆；蓉鼎鲜冒菜店：冒 / 菜店 | 179/192 |
| 螺蛳粉 | 螺蛳 / 粉 | 高新区石螺记螺蛳粉店：螺蛳 / 粉店 | 31/32 |
| 龙湖天街蜜雪冰城 | 龙湖 / 天 / 街 / 蜜 / 雪 / 冰城 | 蜜雪冰城(龙湖·滨江天街店)：滨江 / 江天 / 街，缺独立“天” | 3/4 |

三类查询各保存了一个漏召回目标的真实`_explain`，均为matched=false；全部15个漏召回目标保存了原始字段和索引词项。

为验证补词方向，使用当前容器内同一IK jar、词典目录和Lucene IKTokenizer，在**另一个限内存JVM**中调用Dictionary.addWords；关闭远程词典监测，只改变这个评测进程的内存。没有改ES服务进程、配置或磁盘词典。查询分词88项加漏召回字段分词45项，共133项与真实ES `_analyze`基线逐项一致。

对9562家商户重新分词，比较“查询端smart词项是否全部出现在商户索引端max词项的并集中”：

| 探针 | 原词典词项覆盖 | 加冒菜、螺蛳粉、天街 | 再加10个品牌/校园/道路整词 |
| --- | --- | --- | --- |
| 冒菜 | 179/192 | 192/192 | 192/192 |
| 螺蛳粉 | 31/32 | 32/32 | 32/32 |
| 龙湖天街蜜雪冰城 | 3/4 | 4/4 | 4/4 |
| 茶 | 410/1049 | 410/1049 | 384/1049 |

第二批整词为蜜雪冰城、瑞幸、库迪、茶百道、塔斯汀、袁记云饺、书亦烧仙草、桂苑、科创、合信路。8条品牌查询的既定目标词项覆盖没有增加，查询切成整词更直观，但“茶百道”整体识别后不再为单字“茶”提供原先的词项，造成单字探针少覆盖26家。

**上述补词后数字只表示词项覆盖诊断，不是补词后真实ES召回率、BM25或短语排名。** 独立JVM实验没有运行ES节点或重建带新词库的ES索引，不能推断补词后排名不变。原词典的真实搜索结果与补词后的词项诊断分开保存。

## 原词典阶段建议与当前实施进度

1. 保留当前索引ik_max_word、查询ik_smart方向，先采用跨字段AND约束与完整店名提升作为候选方案；不直接上线本报告权重。
2. 扩展词表仅采用“冒菜、螺蛳粉、天街”，现已按开头所述完成加载与真实搜索回归。三者有真实漏召回证据；不安装来源不明的大词库、不批量导入全部店名，不把停用词表当作消除“店”噪声的首要办法。
3. 品牌整词先列可选项，等品牌查询出现实际问题或明确单字产品规则后再补。当前“被拆开”并没有阻止这8个品牌查询找到预期店。
4. 采用新词库时，索引端与查询端必须配套验证，旧文档不会因词典热更新自动重新分词；本次已重建实验索引并对照原44条查询。正式索引仍为空；未来真实导入/接入时仍需新查询集验收和受控别名切换。
5. 单字子串、拼音、错字、店铺别名分别定义需求。无必要为当前首版一次引入向量语义搜索、拼音插件或大型同义词表。

## 复现、证据和边界

从仓库根目录运行，安装已有[本地数据工具依赖](../../backend/tools/requirements-data.txt)，读取被Git忽略的backend/config/application-local.yml。每次指定新的仓库外目录：

```powershell
python backend/tools/evaluate_restaurant_search.py --run --output D:/eat/.codex-backups/search-evaluation-new
python backend/tools/evaluate_ik_dictionary.py --evidence D:/eat/.codex-backups/search-evaluation-new
python -m unittest discover -s backend/tools -p test_evaluate_restaurant_search.py -v
```

第二条需要本地Docker和当前ES容器，默认名称healthy-agent-elasticsearch，可通过--container指定。独立JVM使用容器自带JDK和当前插件，最多384MB堆、1个处理器；只复制本次UUID的四个/tmp文件并精确清理，不安装新插件。每次运行使用当时的MySQL快照，结果可能随业务资料变化。

独立JVM脚本读取当前服务的词典文件；现在其初始词典已含三个扩展词，不会恢复原词典。要复现原始无扩展词的JVM实验，应在隔离环境加载备份配置，不要对已变更的服务词典套用旧证据；脚本会拒绝基线词项不一致的结果。

本次最终原始证据位于`D:/eat/.codex-backups/search-evaluation-20261104-final/`：

- corpus.json、frozen-cases.json：公开搜索字段快照和冻结的相关ID。
- results.json：SQL及三种ES策略的全量命中ID、前5条、实际DSL、查询分词和环境信息。
- dictionary-analysis.json、miss-analysis.json：候选词分词、漏召回字段词项及真实_explain。
- dictionary-input.tsv64、dictionary-additions.txt、dictionary-output.tsv64、dictionary-diagnostics.json：独立JVM输入、补词批次、输出及133项基线一致性检查。
- cleanup.json：本次实验索引已删除，正式restaurant-search别名及v002文档数保持不变（0）。

两次快照的公开检索字段哈希一致，最终corpus.json的SHA-256为`436cdff49631748ac47e7aa22a686f25c68fc106f2e985bf8a4cad57571f2acc`；冻结查询含相关ID的SHA-256为`f4a7ffc96b091884cf69e7fae08677ba96a05addc4438baf2b36be2000dd9299`。这只核对本实验读取的投影，不声称全库其他字段没有并发变化。

工具安全/查询测试6项通过，覆盖正式资源写入/删除拒绝、精确UUID清理、bulk部分失败与结果ID/索引核验、大整数ID、相关集交集和跨字段AND。未修改Java业务接口或前端，未重新执行无关构建。未做性能压测，不报告p95或速度提升；标签、真实用户人工标注和新词库下的ES排名仍待验证。

## 修改文件与用途

| 文件 | 用途 |
| --- | --- |
| backend/tools/search-evaluation-cases.json | 固定44条查询和预期目标规则 |
| backend/tools/evaluate_restaurant_search.py | 只读快照、SQL/ES对照、词项及解释证据、隔离索引清理 |
| backend/tools/EvaluateIkDictionary.java | 同版Lucene IKTokenizer在独立JVM内的补词实验 |
| backend/tools/evaluate_ik_dictionary.py | 隔离JVM运行、133项基线核对、全量词项覆盖分析 |
| backend/tools/test_evaluate_restaurant_search.py | 6项资源安全、bulk校验及查询语义测试 |
| docs/plans/restaurant-search-evaluation.md | 本报告、结论及44条完整对照 |
| docs/architecture/elasticsearch-schema.md | 索引分词现状与本次评测入口 |
| docs/plans/elasticsearch-search.md | 将探索结论接入后续实施计划 |
| docs/features.md | 面向使用者说明评测完成、正式搜索尚未切换 |
| docs/development.md | 记录证据、环境、限制和仍待验证的部分 |
| docs/README.md | 更新v002/21字段状态并增加报告导航 |
| deploy/elasticsearch/ik/ginkgo-food.dic | 已启用的三个业务扩展词 |
| deploy/elasticsearch/ik/IKAnalyzer.cfg.xml | 通过ext_dict加载词表，作为只读挂载源 |
| deploy/elasticsearch/README.md | 词典持久化、后续维护及恢复说明 |
| D:/healthy-agent/compose.yml（本机仓库外） | 保留原数据卷，增加现有插件/基础词典持久卷与两个文件只读挂载 |

## 44条查询对照

表中数字为全部命中数；“目标”为预先规则标出的商户数，不代表完整语义相关集。

| # | Query | Kind | Target | MySQL | smart_or | smart_and | max_and |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 电子科技大学清水河校区银桦餐厅 | exact | 1 | 1 | 207 | 1 | 1 |
| 2 | 朝阳餐厅(电子科技大学清水河校区店) | exact | 1 | 1 | 6085 | 1 | 1 |
| 3 | 西北餐厅(电子科技大学清水河校区店) | exact | 1 | 1 | 6210 | 1 | 1 |
| 4 | 库迪咖啡(电子科技大学清水河校区店) | exact | 1 | 1 | 6095 | 1 | 1 |
| 5 | 瑞幸咖啡(电子科技大学清水河校区店) | exact | 1 | 1 | 6115 | 2 | 2 |
| 6 | 蜜雪冰城(富士康店) | exact | 1 | 1 | 5984 | 1 | 1 |
| 7 | 兰州牛肉面 | exact | 2 | 19 | 169 | 22 | 22 |
| 8 | 塔斯汀中国汉堡(龙湖天街店) | exact | 1 | 1 | 6871 | 1 | 1 |
| 9 | 银桦 | fragment | 1 | 1 | 1 | 1 | 1 |
| 10 | 朝阳餐厅 | fragment | 1 | 1 | 140 | 1 | 1 |
| 11 | 芙蓉餐厅 | fragment | 1 | 1 | 147 | 1 | 1 |
| 12 | 紫荆餐厅 | fragment | 1 | 1 | 134 | 1 | 1 |
| 13 | 桂苑 | fragment | 12 | 12 | 488 | 12 | 12 |
| 14 | 科创餐厅 | fragment | 3 | 3 | 336 | 3 | 3 |
| 15 | 蜜雪冰城 | brand | 56 | 57 | 73 | 57 | 57 |
| 16 | 瑞幸 | brand | 69 | 70 | 141 | 70 | 70 |
| 17 | 库迪 | brand | 43 | 43 | 60 | 43 | 43 |
| 18 | 茶百道 | brand | 26 | 27 | 571 | 26 | 26 |
| 19 | 塔斯汀 | brand | 10 | 10 | 29 | 10 | 10 |
| 20 | 袁记云饺 | brand | 19 | 20 | 605 | 20 | 20 |
| 21 | 书亦烧仙草 | brand | 20 | 21 | 76 | 21 | 21 |
| 22 | luckin coffee | brand | 13 | 13 | 85 | 13 | 13 |
| 23 | 火锅 | category | 762 | 770 | 770 | 770 | 770 |
| 24 | 螺蛳粉 | category | 32 | 32 | 162 | 31 | 31 |
| 25 | 冒菜 | category | 192 | 192 | 2550 | 180 | 180 |
| 26 | 咖啡 | category | 277 | 281 | 281 | 281 | 281 |
| 27 | 寿司 | category | 16 | 17 | 17 | 17 | 17 |
| 28 | 面 | single | 1026 | 1131 | 747 | 747 | 747 |
| 29 | 茶 | single | 1049 | 1056 | 413 | 413 | 413 |
| 30 | 合信路 | address | 113 | 114 | 4381 | 114 | 114 |
| 31 | 清水河火锅 | combined | 0 | 0 | 793 | 0 | 0 |
| 32 | 清水河咖啡 | combined | 4 | 0 | 300 | 4 | 4 |
| 33 | 龙湖天街蜜雪冰城 | combined | 4 | 0 | 2772 | 3 | 3 |
| 34 | 合信路兰州牛肉面 | combined | 2 | 0 | 4477 | 2 | 2 |
| 35 | 建设路螺蛳粉 | combined | 1 | 0 | 445 | 1 | 1 |
| 36 | 电子科技大学银桦 | combined | 1 | 0 | 77 | 1 | 1 |
| 37 | 银桦食堂 | alias | 1 | 0 | 53 | 0 | 0 |
| 38 | 兰牛 | alias | 18 | 1 | 109 | 0 | 0 |
| 39 | yin hua | pinyin | 1 | 0 | 0 | 0 | 0 |
| 40 | mxbc | pinyin | 56 | 0 | 0 | 0 | 0 |
| 41 | 银华餐厅 | typo | 1 | 0 | 703 | 0 | 0 |
| 42 | 蜜雪冰成 | typo | 56 | 0 | 72 | 0 | 0 |
| 43 | 量子独角兽餐厅987654321 | negative | 0 | 0 | 134 | 0 | 0 |
| 44 | 不存在的星际拉面站987654321 | negative | 0 | 0 | 1277 | 0 | 0 |

## 逐条分词与前5条结果

下列名称和ID来自本次真实查询；完整地址、分数和另外两种策略的结果保存在仓库外results.json中。

<details>
<summary>01. 电子科技大学清水河校区银桦餐厅</summary>

- `ik_smart`: `电子` / `科技大学` / `清水河` / `校区` / `银桦` / `餐厅`
- `ik_max_word`: `电子科技` / `电子科` / `电子` / `科技大学` / `科技大` / `科技` / `大学` / `清水河` / `清水` / `河` / `校区` / `银桦` / `餐厅`

| Engine | Top 5 (ID: name) | Target hit in top 5 |
| --- | --- | --- |
| mysql | 457: 电子科技大学清水河校区银桦餐厅 | 1 |
| smart_and | 457: 电子科技大学清水河校区银桦餐厅 | 1 |

</details>

<details>
<summary>02. 朝阳餐厅(电子科技大学清水河校区店)</summary>

- `ik_smart`: `朝阳` / `餐厅` / `电子` / `科技大学` / `清水河` / `校区` / `店`
- `ik_max_word`: `朝阳` / `餐厅` / `电子科技` / `电子科` / `电子` / `科技大学` / `科技大` / `科技` / `大学` / `清水河` / `清水` / `河` / `校区` / `店`

| Engine | Top 5 (ID: name) | Target hit in top 5 |
| --- | --- | --- |
| mysql | 450: 朝阳餐厅(电子科技大学清水河校区店) | 1 |
| smart_and | 450: 朝阳餐厅(电子科技大学清水河校区店) | 1 |

</details>

<details>
<summary>03. 西北餐厅(电子科技大学清水河校区店)</summary>

- `ik_smart`: `西北` / `餐厅` / `电子` / `科技大学` / `清水河` / `校区` / `店`
- `ik_max_word`: `西北` / `餐厅` / `电子科技` / `电子科` / `电子` / `科技大学` / `科技大` / `科技` / `大学` / `清水河` / `清水` / `河` / `校区` / `店`

| Engine | Top 5 (ID: name) | Target hit in top 5 |
| --- | --- | --- |
| mysql | 453: 西北餐厅(电子科技大学清水河校区店) | 1 |
| smart_and | 453: 西北餐厅(电子科技大学清水河校区店) | 1 |

</details>

<details>
<summary>04. 库迪咖啡(电子科技大学清水河校区店)</summary>

- `ik_smart`: `库` / `迪` / `咖啡` / `电子` / `科技大学` / `清水河` / `校区` / `店`
- `ik_max_word`: `库` / `迪` / `咖啡` / `电子科技` / `电子科` / `电子` / `科技大学` / `科技大` / `科技` / `大学` / `清水河` / `清水` / `河` / `校区` / `店`

| Engine | Top 5 (ID: name) | Target hit in top 5 |
| --- | --- | --- |
| mysql | 448: 库迪咖啡(电子科技大学清水河校区店) | 1 |
| smart_and | 448: 库迪咖啡(电子科技大学清水河校区店) | 1 |

</details>

<details>
<summary>05. 瑞幸咖啡(电子科技大学清水河校区店)</summary>

- `ik_smart`: `瑞` / `幸` / `咖啡` / `电子` / `科技大学` / `清水河` / `校区` / `店`
- `ik_max_word`: `瑞` / `幸` / `咖啡` / `电子科技` / `电子科` / `电子` / `科技大学` / `科技大` / `科技` / `大学` / `清水河` / `清水` / `河` / `校区` / `店`

| Engine | Top 5 (ID: name) | Target hit in top 5 |
| --- | --- | --- |
| mysql | 456: 瑞幸咖啡(电子科技大学清水河校区店) | 1 |
| smart_and | 456: 瑞幸咖啡(电子科技大学清水河校区店)<br>1365: 瑞幸咖啡(电子科大科创餐厅店) | 1 |

</details>

<details>
<summary>06. 蜜雪冰城(富士康店)</summary>

- `ik_smart`: `蜜` / `雪` / `冰城` / `富士康` / `店`
- `ik_max_word`: `蜜` / `雪` / `冰城` / `富士康` / `富士` / `康` / `店`

| Engine | Top 5 (ID: name) | Target hit in top 5 |
| --- | --- | --- |
| mysql | 129: 蜜雪冰城(富士康店) | 1 |
| smart_and | 129: 蜜雪冰城(富士康店) | 1 |

</details>

<details>
<summary>07. 兰州牛肉面</summary>

- `ik_smart`: `兰州` / `牛肉面`
- `ik_max_word`: `兰州` / `牛肉面` / `牛肉` / `面`

| Engine | Top 5 (ID: name) | Target hit in top 5 |
| --- | --- | --- |
| mysql | 1628: 伊顺源兰州牛肉面(金牛万达广场C组团店)<br>131: 兰州牛肉面<br>508: 兰州牛肉面<br>5057: 兰州牛肉面(蓝光COCO时代6栋店)<br>1014: 兰州牛肉面(龙湖·时代天街店) | 2 |
| smart_and | 131: 兰州牛肉面<br>508: 兰州牛肉面<br>6036: 西部香兰州牛肉面<br>1904: 塞拉宫兰州牛肉面(清真)<br>1014: 兰州牛肉面(龙湖·时代天街店) | 2 |

</details>

<details>
<summary>08. 塔斯汀中国汉堡(龙湖天街店)</summary>

- `ik_smart`: `塔` / `斯` / `汀` / `中国` / `汉堡` / `龙湖` / `天` / `街` / `店`
- `ik_max_word`: `塔` / `斯` / `汀` / `中国` / `汉堡` / `龙湖` / `天` / `街` / `店`

| Engine | Top 5 (ID: name) | Target hit in top 5 |
| --- | --- | --- |
| mysql | 812: 塔斯汀中国汉堡(龙湖天街店) | 1 |
| smart_and | 812: 塔斯汀中国汉堡(龙湖天街店) | 1 |

</details>

<details>
<summary>09. 银桦</summary>

- `ik_smart`: `银桦`
- `ik_max_word`: `银桦`

| Engine | Top 5 (ID: name) | Target hit in top 5 |
| --- | --- | --- |
| mysql | 457: 电子科技大学清水河校区银桦餐厅 | 1 |
| smart_and | 457: 电子科技大学清水河校区银桦餐厅 | 1 |

</details>

<details>
<summary>10. 朝阳餐厅</summary>

- `ik_smart`: `朝阳` / `餐厅`
- `ik_max_word`: `朝阳` / `餐厅`

| Engine | Top 5 (ID: name) | Target hit in top 5 |
| --- | --- | --- |
| mysql | 450: 朝阳餐厅(电子科技大学清水河校区店) | 1 |
| smart_and | 450: 朝阳餐厅(电子科技大学清水河校区店) | 1 |

</details>

<details>
<summary>11. 芙蓉餐厅</summary>

- `ik_smart`: `芙蓉` / `餐厅`
- `ik_max_word`: `芙蓉` / `餐厅`

| Engine | Top 5 (ID: name) | Target hit in top 5 |
| --- | --- | --- |
| mysql | 461: 芙蓉餐厅(电子科技大学清水河校区店) | 1 |
| smart_and | 461: 芙蓉餐厅(电子科技大学清水河校区店) | 1 |

</details>

<details>
<summary>12. 紫荆餐厅</summary>

- `ik_smart`: `紫荆` / `餐厅`
- `ik_max_word`: `紫荆` / `餐厅`

| Engine | Top 5 (ID: name) | Target hit in top 5 |
| --- | --- | --- |
| mysql | 463: 紫荆餐厅(电子科技大学清水河校区店) | 1 |
| smart_and | 463: 紫荆餐厅(电子科技大学清水河校区店) | 1 |

</details>

<details>
<summary>13. 桂苑</summary>

- `ik_smart`: `桂` / `苑`
- `ik_max_word`: `桂` / `苑`

| Engine | Top 5 (ID: name) | Target hit in top 5 |
| --- | --- | --- |
| mysql | 1325: 今喜CAKE(清桂苑店)<br>1314: 巴蜀·火锅冒菜(清桂苑店)<br>1332: 懒的爽冒菜(清桂苑店)<br>8386: 招牌冰粉土凉虾(东桂苑小区柒号院店)<br>1266: 无茗糖水铺(清桂苑店) | 5 |
| smart_and | 1338: 馋猫(清桂苑店)<br>1257: 翁记粉(清桂苑店)<br>1325: 今喜CAKE(清桂苑店)<br>1343: 本色鲜卤(清桂苑店)<br>1266: 无茗糖水铺(清桂苑店) | 5 |

</details>

<details>
<summary>14. 科创餐厅</summary>

- `ik_smart`: `科` / `创` / `餐厅`
- `ik_max_word`: `科` / `创` / `餐厅`

| Engine | Top 5 (ID: name) | Target hit in top 5 |
| --- | --- | --- |
| mysql | 1209: 杏福里食光科创餐厅<br>1365: 瑞幸咖啡(电子科大科创餐厅店)<br>1311: 科创餐厅 | 3 |
| smart_and | 1311: 科创餐厅<br>1209: 杏福里食光科创餐厅<br>1365: 瑞幸咖啡(电子科大科创餐厅店) | 3 |

</details>

<details>
<summary>15. 蜜雪冰城</summary>

- `ik_smart`: `蜜` / `雪` / `冰城`
- `ik_max_word`: `蜜` / `雪` / `冰城`

| Engine | Top 5 (ID: name) | Target hit in top 5 |
| --- | --- | --- |
| mysql | 2885: 聚源火焰山西昌火盆烧烤(商业场店)<br>632: 蜜雪冰城<br>8692: 蜜雪冰城(SM广场店)<br>267: 蜜雪冰城(万景峰店)<br>7663: 蜜雪冰城(万科·钻石广场店) | 4 |
| smart_and | 632: 蜜雪冰城<br>3233: 蜜雪冰城(龙城店)<br>2634: 蜜雪冰城(赛格广场店)<br>2878: 蜜雪冰城(商业场店)<br>3080: 蜜雪冰城(驷马桥店) | 5 |

</details>

<details>
<summary>16. 瑞幸</summary>

- `ik_smart`: `瑞` / `幸`
- `ik_max_word`: `瑞` / `幸`

| Engine | Top 5 (ID: name) | Target hit in top 5 |
| --- | --- | --- |
| mysql | 9079: Daji cafe妲己主题露台花园餐厅<br>9297: luckin coffee 瑞幸咖啡(万科·天荟店)<br>6089: luckin coffee 瑞幸咖啡(世茂大厦店)<br>6037: luckin coffee 瑞幸咖啡(亚太并购大厦店)<br>9457: luckin coffee 瑞幸咖啡(多弗汇店) | 4 |
| smart_and | 516: 瑞幸咖啡<br>7262: 瑞幸咖啡<br>1179: 瑞幸咖啡(无限魔方店)<br>2205: 瑞幸咖啡(文殊坊店)<br>2267: 瑞幸咖啡(凯乐广场店) | 5 |

</details>

<details>
<summary>17. 库迪</summary>

- `ik_smart`: `库` / `迪`
- `ik_max_word`: `库` / `迪`

| Engine | Top 5 (ID: name) | Target hit in top 5 |
| --- | --- | --- |
| mysql | 5421: 库迪咖啡(万谷智慧产业园店)<br>9271: 库迪咖啡(东郊记忆店)<br>6022: 库迪咖啡(亚太并购大厦店)<br>7520: 库迪咖啡(优客城市奥莱店)<br>6539: 库迪咖啡(华兴东街亚朵酒店大堂店) | 5 |
| smart_and | 9271: 库迪咖啡(东郊记忆店)<br>1096: 库迪咖啡(红光龙城国际店)<br>6022: 库迪咖啡(亚太并购大厦店)<br>180: 库迪咖啡(合庆里华莱士店)<br>2698: 库迪咖啡(成都恒大广场店) | 5 |

</details>

<details>
<summary>18. 茶百道</summary>

- `ik_smart`: `茶` / `百` / `道`
- `ik_max_word`: `茶` / `百` / `道`

| Engine | Top 5 (ID: name) | Target hit in top 5 |
| --- | --- | --- |
| mysql | 9284: 失控猫·元気烧肉饭(龙湖·滨江天街店)<br>6779: 茶百道(339店)<br>1870: 茶百道(上城天街店)<br>9015: 茶百道(中房·红枫岭店)<br>7175: 茶百道(公元汇店) | 4 |
| smart_and | 6779: 茶百道(339店)<br>403: 茶百道(龙城国际店)<br>1498: 茶百道(纺专店)<br>2746: 茶百道(大墙东街店)<br>4286: 茶百道(财富城店) | 5 |

</details>

<details>
<summary>19. 塔斯汀</summary>

- `ik_smart`: `塔` / `斯` / `汀`
- `ik_max_word`: `塔` / `斯` / `汀`

| Engine | Top 5 (ID: name) | Target hit in top 5 |
| --- | --- | --- |
| mysql | 5458: 塔斯汀中国汉堡(博闻·时代锦汇华庭店)<br>2631: 塔斯汀中国汉堡(太升南路店)<br>7165: 塔斯汀中国汉堡(太阳公元公元汇店)<br>8050: 塔斯汀中国汉堡(小龙桥店)<br>1083: 塔斯汀中国汉堡(成都市红光银润店) | 5 |
| smart_and | 2382: 塔斯汀中国汉堡(文殊坊店)<br>8050: 塔斯汀中国汉堡(小龙桥店)<br>812: 塔斯汀中国汉堡(龙湖天街店)<br>2631: 塔斯汀中国汉堡(太升南路店)<br>4417: 塔斯汀中国汉堡(曹家巷店) | 5 |

</details>

<details>
<summary>20. 袁记云饺</summary>

- `ik_smart`: `袁` / `记` / `云` / `饺`
- `ik_max_word`: `袁` / `记` / `云` / `饺`

| Engine | Top 5 (ID: name) | Target hit in top 5 |
| --- | --- | --- |
| mysql | 1091: 库迪咖啡(四川传媒学院东区店)<br>4572: 袁记云饺(Discovery王府井店)<br>9509: 袁记云饺(九龙仓御园店)<br>5103: 袁记云饺(成都市成华区印象城店)<br>7664: 袁记云饺(成都市成华区建兴路店) | 4 |
| smart_and | 7104: 袁记云饺(望平街店)<br>4572: 袁记云饺(Discovery王府井店)<br>989: 袁记云饺(龙湖成都时代天街店)<br>1664: 袁记云饺(金牛万达广场店)<br>933: 袁记云饺(成都市时代天骄路店) | 5 |

</details>

<details>
<summary>21. 书亦烧仙草</summary>

- `ik_smart`: `书` / `亦` / `烧` / `仙草`
- `ik_max_word`: `书` / `亦` / `烧` / `仙草`

| Engine | Top 5 (ID: name) | Target hit in top 5 |
| --- | --- | --- |
| mysql | 8761: 书亦烧仙草(SM广场店)<br>256: 书亦烧仙草(万景峰店)<br>4235: 书亦烧仙草(三友路店)<br>2624: 书亦烧仙草(太升南路店)<br>2882: 书亦烧仙草(总府路店) | 5 |
| smart_and | 2882: 书亦烧仙草(总府路店)<br>1111: 书亦烧仙草(红光里店)<br>8761: 书亦烧仙草(SM广场店)<br>256: 书亦烧仙草(万景峰店)<br>2624: 书亦烧仙草(太升南路店) | 5 |

</details>

<details>
<summary>22. luckin coffee</summary>

- `ik_smart`: `luckin` / `coffee`
- `ik_max_word`: `luckin` / `coffee`

| Engine | Top 5 (ID: name) | Target hit in top 5 |
| --- | --- | --- |
| mysql | 9297: luckin coffee 瑞幸咖啡(万科·天荟店)<br>6089: luckin coffee 瑞幸咖啡(世茂大厦店)<br>6037: luckin coffee 瑞幸咖啡(亚太并购大厦店)<br>9457: luckin coffee 瑞幸咖啡(多弗汇店)<br>7984: luckin coffee 瑞幸咖啡(成都玉双路地铁站汉庭优佳酒店店) | 5 |
| smart_and | 2316: luckin coffee 瑞幸咖啡(草市街店)<br>6037: luckin coffee 瑞幸咖啡(亚太并购大厦店)<br>6089: luckin coffee 瑞幸咖啡(世茂大厦店)<br>9457: luckin coffee 瑞幸咖啡(多弗汇店)<br>4292: luckin coffee 瑞幸咖啡(银海中心店) | 5 |

</details>

<details>
<summary>23. 火锅</summary>

- `ik_smart`: `火锅`
- `ik_max_word`: `火锅`

| Engine | Top 5 (ID: name) | Target hit in top 5 |
| --- | --- | --- |
| mysql | 7537: 107老字号串串香<br>323: 168重庆美蛙鱼火锅<br>4226: 666老火锅店<br>9500: 88号重庆小火锅<br>5824: FREEWAY公路火锅 | 5 |
| smart_and | 666: 劲火锅<br>8388: 烫火锅(重庆原味老火锅)<br>1035: 老爹有料火锅<br>1207: 鲜牛肉火锅<br>1210: 鲜毛肚火锅 | 5 |

</details>

<details>
<summary>24. 螺蛳粉</summary>

- `ik_smart`: `螺蛳` / `粉`
- `ik_max_word`: `螺蛳` / `粉`

| Engine | Top 5 (ID: name) | Target hit in top 5 |
| --- | --- | --- |
| mysql | 3609: 77螺蛳粉<br>787: 一之螺·螺蛳粉(成都时代天街店)<br>1281: 一之螺·螺蛳粉(电子科大店)<br>820: 八桂唐记柳州螺蛳粉<br>3483: 利品福柳州螺蛳粉(蓝光·金荷花店) | 5 |
| smart_and | 4688: 螺蛳粉<br>3609: 77螺蛳粉<br>1682: 螺总螺蛳粉<br>667: 螺一螺螺蛳粉<br>1834: 莹雯卤味螺蛳粉 | 5 |

</details>

<details>
<summary>25. 冒菜</summary>

- `ik_smart`: `冒` / `菜`
- `ik_max_word`: `冒` / `菜`

| Engine | Top 5 (ID: name) | Target hit in top 5 |
| --- | --- | --- |
| mysql | 5768: 一品鲜冒菜(蓥华寺街)<br>9557: 万年友和冒菜<br>8384: 三国冒菜冒烤鸭<br>5434: 三故冒菜(嘉华阁店)<br>2981: 三里铺老街冒菜 | 5 |
| smart_and | 2603: 私家冒菜<br>2683: 冒菜香<br>2769: 小院冒菜<br>3541: 兄弟冒菜<br>4452: 关刀冒菜 | 5 |

</details>

<details>
<summary>26. 咖啡</summary>

- `ik_smart`: `咖啡`
- `ik_max_word`: `咖啡`

| Engine | Top 5 (ID: name) | Target hit in top 5 |
| --- | --- | --- |
| mysql | 1672: 5Coffee咖啡(成都金牛万达广场甲级写字楼C座店)<br>9132: 61便民咖啡(杉板桥社区店)<br>7855: Amigos Coffee阿米构思咖啡<br>8620: A咖啡<br>6618: A咖啡(春熙路时代百盛店) | 5 |
| smart_and | 8620: A咖啡<br>2201: 王爷咖啡<br>2706: 卜居咖啡<br>2897: 栖木咖啡<br>3620: 遂宁咖啡 | 5 |

</details>

<details>
<summary>27. 寿司</summary>

- `ik_smart`: `寿司`
- `ik_max_word`: `寿司`

| Engine | Top 5 (ID: name) | Target hit in top 5 |
| --- | --- | --- |
| mysql | 9381: 争鲜寿司(龙湖·滨江天街店)<br>6881: 千岛寿司<br>6640: 千渔寿司(IFS国际金融中心店)<br>8247: 半藏寿司Sushi Hanzo(成都太古里店)<br>756: 华谊茶府 | 4 |
| smart_and | 6881: 千岛寿司<br>3184: 私町OSAKA寿司<br>6186: 德林寿司(春熙路店)<br>8315: 黑眼熊寿司(东门店)<br>806: 角谷屋寿司(龙湖店) | 5 |

</details>

<details>
<summary>28. 面</summary>

- `ik_smart`: `面`
- `ik_max_word`: `面`

| Engine | Top 5 (ID: name) | Target hit in top 5 |
| --- | --- | --- |
| mysql | 409: 11号面馆(尚景岁月店)<br>3243: 132杂酱面<br>3988: 1987内江牛肉面<br>7026: 311姜鸭面(望平街店)<br>9163: BIGFISH TAPROOM(大鱼精酿酒馆) | 4 |
| smart_and | 9031: 面华豪(豪华面)<br>3939: 牛肉面姜鸭面<br>1988: 自家面<br>2105: 海味面<br>2764: 公婆面 | 5 |

</details>

<details>
<summary>29. 茶</summary>

- `ik_smart`: `茶`
- `ik_max_word`: `茶`

| Engine | Top 5 (ID: name) | Target hit in top 5 |
| --- | --- | --- |
| mysql | 170: 168球韵茶府<br>1537: 1989茶府<br>4877: 369茶坊(秀苑东路店)<br>4882: 518茶坊(秀苑东路店)<br>1095: 8号茶楼(龍城国际五期店) | 5 |
| smart_and | 1907: 茶御茶<br>5866: 茶煮炉围茶<br>686: 茶咖<br>1143: 茶不醉<br>7659: 茶语茶府(建设路店) | 5 |

</details>

<details>
<summary>30. 合信路</summary>

- `ik_smart`: `合` / `信` / `路`
- `ik_max_word`: `合` / `信` / `路`

| Engine | Top 5 (ID: name) | Target hit in top 5 |
| --- | --- | --- |
| mysql | 170: 168球韵茶府<br>907: CY烧烤吧<br>615: 一江亭·鲜切牛肉涮烤自助火锅(龙湖时代天街店)<br>142: 三顾冒菜(合信路店)<br>920: 东坡养身肴特色汤锅 | 5 |
| smart_and | 912: 鸡柳大人(合信路店)<br>142: 三顾冒菜(合信路店)<br>162: 八二抄手(合庆里店)<br>229: 合庆冒菜(和康生活广场店)<br>181: 书亦烧仙草(高新区合庆里店) | 4 |

</details>

<details>
<summary>31. 清水河火锅</summary>

- `ik_smart`: `清水河` / `火锅`
- `ik_max_word`: `清水河` / `清水` / `河` / `火锅`

| Engine | Top 5 (ID: name) | Target hit in top 5 |
| --- | --- | --- |
| mysql | (empty) | 0 |
| smart_and | (empty) | 0 |

</details>

<details>
<summary>32. 清水河咖啡</summary>

- `ik_smart`: `清水河` / `咖啡`
- `ik_max_word`: `清水河` / `清水` / `河` / `咖啡`

| Engine | Top 5 (ID: name) | Target hit in top 5 |
| --- | --- | --- |
| mysql | (empty) | 0 |
| smart_and | 448: 库迪咖啡(电子科技大学清水河校区店)<br>456: 瑞幸咖啡(电子科技大学清水河校区店)<br>1352: 宾诺咖啡(龙湖·时代天街东南)<br>1365: 瑞幸咖啡(电子科大科创餐厅店) | 4 |

</details>

<details>
<summary>33. 龙湖天街蜜雪冰城</summary>

- `ik_smart`: `龙湖` / `天` / `街` / `蜜` / `雪` / `冰城`
- `ik_max_word`: `龙湖` / `天` / `街` / `蜜` / `雪` / `冰城`

| Engine | Top 5 (ID: name) | Target hit in top 5 |
| --- | --- | --- |
| mysql | (empty) | 0 |
| smart_and | 202: 蜜雪冰城(龙湖时代天街店)<br>866: 蜜雪冰城(龙湖天街18栋店)<br>715: 蜜雪冰城(龙湖旗舰店) | 3 |

</details>

<details>
<summary>34. 合信路兰州牛肉面</summary>

- `ik_smart`: `合` / `信` / `路` / `兰州` / `牛肉面`
- `ik_max_word`: `合` / `信` / `路` / `兰州` / `牛肉面` / `牛肉` / `面`

| Engine | Top 5 (ID: name) | Target hit in top 5 |
| --- | --- | --- |
| mysql | (empty) | 0 |
| smart_and | 131: 兰州牛肉面<br>186: 清真兰州牛肉面(高新青年公寓4号苑店) | 2 |

</details>

<details>
<summary>35. 建设路螺蛳粉</summary>

- `ik_smart`: `建设路` / `螺蛳` / `粉`
- `ik_max_word`: `建设路` / `建设` / `路` / `螺蛳` / `粉`

| Engine | Top 5 (ID: name) | Target hit in top 5 |
| --- | --- | --- |
| mysql | (empty) | 0 |
| smart_and | 7658: 慕吃螺蛳粉(万科·钻石广场店) | 1 |

</details>

<details>
<summary>36. 电子科技大学银桦</summary>

- `ik_smart`: `电子` / `科技大学` / `银桦`
- `ik_max_word`: `电子科技` / `电子科` / `电子` / `科技大学` / `科技大` / `科技` / `大学` / `银桦`

| Engine | Top 5 (ID: name) | Target hit in top 5 |
| --- | --- | --- |
| mysql | (empty) | 0 |
| smart_and | 457: 电子科技大学清水河校区银桦餐厅 | 1 |

</details>

<details>
<summary>37. 银桦食堂</summary>

- `ik_smart`: `银桦` / `食堂`
- `ik_max_word`: `银桦` / `食堂`

| Engine | Top 5 (ID: name) | Target hit in top 5 |
| --- | --- | --- |
| mysql | (empty) | 0 |
| smart_and | (empty) | 0 |

</details>

<details>
<summary>38. 兰牛</summary>

- `ik_smart`: `兰` / `牛`
- `ik_max_word`: `兰` / `牛`

| Engine | Top 5 (ID: name) | Target hit in top 5 |
| --- | --- | --- |
| mysql | 4418: 穆兰牛肉面 | 0 |
| smart_and | (empty) | 0 |

</details>

<details>
<summary>39. yin hua</summary>

- `ik_smart`: `yin` / `hua`
- `ik_max_word`: `yin` / `hua`

| Engine | Top 5 (ID: name) | Target hit in top 5 |
| --- | --- | --- |
| mysql | (empty) | 0 |
| smart_and | (empty) | 0 |

</details>

<details>
<summary>40. mxbc</summary>

- `ik_smart`: `mxbc`
- `ik_max_word`: `mxbc`

| Engine | Top 5 (ID: name) | Target hit in top 5 |
| --- | --- | --- |
| mysql | (empty) | 0 |
| smart_and | (empty) | 0 |

</details>

<details>
<summary>41. 银华餐厅</summary>

- `ik_smart`: `银` / `华` / `餐厅`
- `ik_max_word`: `银` / `华` / `餐厅`

| Engine | Top 5 (ID: name) | Target hit in top 5 |
| --- | --- | --- |
| mysql | (empty) | 0 |
| smart_and | (empty) | 0 |

</details>

<details>
<summary>42. 蜜雪冰成</summary>

- `ik_smart`: `蜜` / `雪` / `冰成`
- `ik_max_word`: `蜜` / `雪` / `冰成`

| Engine | Top 5 (ID: name) | Target hit in top 5 |
| --- | --- | --- |
| mysql | (empty) | 0 |
| smart_and | (empty) | 0 |

</details>

<details>
<summary>43. 量子独角兽餐厅987654321</summary>

- `ik_smart`: `量子` / `独角兽` / `餐厅` / `987654321`
- `ik_max_word`: `量子` / `独角兽` / `独角` / `兽` / `餐厅` / `987654321`

| Engine | Top 5 (ID: name) | Target hit in top 5 |
| --- | --- | --- |
| mysql | (empty) | 0 |
| smart_and | (empty) | 0 |

</details>

<details>
<summary>44. 不存在的星际拉面站987654321</summary>

- `ik_smart`: `不存在` / `的` / `星际` / `拉面` / `站` / `987654321`
- `ik_max_word`: `不存在` / `不存` / `存在` / `的` / `星际` / `拉面` / `站` / `987654321`

| Engine | Top 5 (ID: name) | Target hit in top 5 |
| --- | --- | --- |
| mysql | (empty) | 0 |
| smart_and | (empty) | 0 |

</details>
