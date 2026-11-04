# 本地IK业务扩展词典

2026-11-04已在本地Elasticsearch/IK 9.5.4启用第一版词表：冒菜、螺蛳粉、天街。仅影响本地ES分词，不开启语义搜索，也不切换网站的MySQL查询入口。

## 文件与实际挂载

- `ik/ginkgo-food.dic`：UTF-8、无BOM、每行一个词；只收录已验证的业务词，不批量导入店名、拼音或同义词。
- `ik/IKAnalyzer.cfg.xml`：通过ext_dict登记ginkgo-food.dic，保留内置基础词典，不新增停用词或远程词库。
- 两个文件都由`D:/healthy-agent/compose.yml`只读挂载到`/usr/share/elasticsearch/config/analysis-ik/`下同名文件。该Compose属于本机已有基础设施，仓库根compose.yaml未增加ES服务。

本机原先只持久化ES数据，IK插件和基础词典在容器可写层。此次先完整备份、复制到两个外部持久卷，再重建同一个ES服务。实际挂载为：

| 内容 | 来源 | 容器目标 |
| --- | --- | --- |
| 原有ES数据 | healthy-agent_agent_elasticsearch_data | /usr/share/elasticsearch/data |
| 现有IK 9.5.4插件 | healthy-agent_agent_elasticsearch_ik_plugin | /usr/share/elasticsearch/plugins/analysis-ik |
| 现有IK基础词典 | healthy-agent_agent_elasticsearch_ik_config | /usr/share/elasticsearch/config/analysis-ik |
| 本仓库IK配置 | D:/eat/ginkgo-food-map-v2/deploy/elasticsearch/ik/IKAnalyzer.cfg.xml | 上述配置目录/IKAnalyzer.cfg.xml，只读 |
| 本仓库扩展词表 | D:/eat/ginkgo-food-map-v2/deploy/elasticsearch/ik/ginkgo-food.dic | 上述配置目录/ginkgo-food.dic，只读 |

两个IK卷在Compose中声明external=true并显式指定上述name，避免当成Compose管理的新卷。首次迁入时已装入原有插件/基础词典，不能在新机器上只创建空卷就认为IK可用；应先安装匹配版本并准备基础词典。没有把二进制插件或完整基础词库提交到本仓库。

## 后续维护

1. 将新词加入ginkgo-food.dic，保持UTF-8和一行一词；词库变更也要记录评测证据。
2. 本地词典在启动时加载，修改文件后重启本地ES：

   ```powershell
   docker compose -f D:/healthy-agent/compose.yml config --quiet
   docker compose -f D:/healthy-agent/compose.yml restart elasticsearch
   ```

3. 等待ES恢复后用`POST /restaurant-search/_analyze`分别测试ik_smart和ik_max_word，例如“冒菜馆”“螺蛳粉店”“滨江天街”。仅看文件内容不能证明服务已加载。
4. 已索引文档不会因词库更新自动重新分词；必须重建新的物理索引后对照查询，再决定别名切换。此次正式v002为空，无需重建正式业务数据；只重建了随机实验索引并在完成后清理。其他已有索引文档原样保留，没有替其他应用重建索引。
5. 对照脚本及完整结果见[搜索评测](../../docs/plans/restaurant-search-evaluation.md)。结构初始化工具的--check只核对mapping/settings/别名，不检查外部词典；必须另外核对挂载、词表内容及真实_analyze。

目前只有本机Compose登记了这些绝对路径；移动仓库需要同步修改两个bind来源。部署到其他环境时，先显式安排兼容插件、词典持久化和数据重建，不直接使用本机路径。

## 备份与恢复

本次备份位于`D:/eat/.codex-backups/ik-install-20261104/`：compose.before.yml、原插件/词典目录、重建前后索引计数与别名、comparison.json。新增词表和配置的SHA-256及加载验证记录见verification.json。

需要撤回这三个词时，在已持久化的当前部署中把ext_dict改为空值（或挂载经核对的旧配置），重启ES，再对已有索引另行决定重建；只恢复查询词典不能重写已经生成的词项。不要直接用原Compose重建并丢掉新增挂载，否则原本容器内安装的IK插件也不会自动出现在官方基础镜像中。

本次没有修改MySQL、业务代码或API；网站继续使用MySQL搜索。原索引文档数和别名已核对保留，实际搜索回归没有替代未来的语义搜索、拼音、单字子串或人工相关性验收。
