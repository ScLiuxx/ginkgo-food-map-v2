# 腾讯云Elasticsearch与IK首次配置

前置为Docker可用，已下载ES9.5.4官方镜像。该步骤独立于`/opt/foodmap-infra`的MySQL／Redis配置，不启动Kibana，不创建业务索引或导入开发数据。

## 传输、初始化和启动

通常在Windows PowerShell传输本仓库的完整elasticsearch目录：

```powershell
scp -r D:\eat\foodmap\deploy\elasticsearch ubuntu@43.139.87.34:~/
```

服务器SSH终端执行`sudo python3 ~/elasticsearch/server/prepare.py`。脚本从官方源下载IK；若`server/analysis-ik.zip`已存在，则优先核对并使用它，不再下载。插件SHA-256及版本固定，来源和哈希不接受前端参数。

本轮已在仓库外准备带官方安装包的传输目录`D:\eat\.codex-previews\elasticsearch-server-20261119\foodmap-elasticsearch-setup\`，可直接传输以免服务器重复下载。该目录保留`server/`和`ik/`结构，初始化入口相应改为`~/foodmap-elasticsearch-setup/server/prepare.py`。二进制包不纳入Git。

看到`Elasticsearch configuration ready`后：

```bash
sudo docker compose -f /opt/foodmap-search/compose.yaml build elasticsearch
sudo docker compose -f /opt/foodmap-search/compose.yaml up -d --no-build --wait --wait-timeout 240
sudo docker compose -f /opt/foodmap-search/compose.yaml ps
curl --fail http://127.0.0.1:9200/_cat/plugins?v
curl --fail -H 'Content-Type: application/json' http://127.0.0.1:9200/_analyze -d '{"analyzer":"ik_smart","text":"冒菜 螺蛳粉 天街"}'
```

插件列表应包含`analysis-ik 9.5.4`，分词结果应包含三个扩展词。`_analyze`只执行内存分词，不写入索引；容器healthy只证明集群可达，不能代替插件、词典和业务索引验收。2026-11-19用户回传容器healthy、9200仅绑定127.0.0.1，插件列表含`analysis-ik 9.5.4`；扩展词典分词及业务索引仍待回传核对。`ps`中的`9300/tcp`没有宿主机映射，不表示公网发布了9300。

## 创建空业务索引

确认分词结果后，使用现有[初始化工具](../../../backend/tools/setup_restaurant_search_index.py)及[当前索引定义](../../../backend/src/main/resources/elasticsearch/restaurant-search-v004.json)。工具只访问服务器本机ES，不依赖额外Python包；创建空索引时原子绑定别名，不导入商户，已有资源冲突时拒绝覆盖。

首次教学已将上述两个源码文件按原相对目录打包到仓库外`D:\eat\.codex-previews\elasticsearch-server-20261119\foodmap-search-schema\`，目录包含`tools/`与`src/main/resources/elasticsearch/`。Windows PowerShell传输：

```powershell
scp -r D:\eat\.codex-previews\elasticsearch-server-20261119\foodmap-search-schema ubuntu@43.139.87.34:~/
```

服务器SSH终端执行：

```bash
python3 ~/foodmap-search-schema/tools/setup_restaurant_search_index.py
python3 ~/foodmap-search-schema/tools/setup_restaurant_search_index.py --check
```

首次创建应返回`created: true`，再次只读检查返回`created: false`，物理索引为`restaurant-search-v004`、别名为`restaurant-search`。2026-11-19用户回传上述只读检查成功，`documentCount: 0`，确认当前空索引结构与别名符合定义；分词结果尚未回传，不能据此认定扩展词典已验收。后续数据同步由应用负责。失败时保留错误输出，不删除或重建既有索引。此步仅为部署初始化，不是自动化测试，不以本机开发业务ES为验证目标。

## 配置与持久化

- ES版本固定9.5.4，基础镜像固定摘要`82ac14f43fe701992e601f4cc81e1c0d7dbc5a2576d8cd736006452925df4026`，与用户拉取结果及本地官方镜像一致。
- IK安装包来自`https://get.infini.cloud/elasticsearch/analysis-ik/9.5.4`，SHA-256为`e6705018287c3e101233189a36d636bd7d1f00293cd02daa94d23e728ad6407c`。脚本同时检查插件名、插件版本、目标ES版本及基础词典。Dockerfile从本地文件安装插件，插件和基础词典包含在派生镜像中；重建容器不会依赖旧容器可写层。
- 数据保存在`foodmap-search_elasticsearch_data`命名卷。两个自有词典文件复制到`/opt/foodmap-search/ik/`后只读挂载，复用[仓库词典](../README.md)，不复制本机ES数据卷。词典更新不会自动重建已索引文档。
- 使用单节点、1GiB JVM堆、2GiB容器内存上限，为非堆内存及文件缓存保留空间；关闭本轮不用的机器学习及GeoIP自动下载，不安装Kibana。
- 按现有后端无认证本机连接能力设置`xpack.security.enabled=false`，9200仅发布到`127.0.0.1`，9300不发布。此配置的隔离边界为宿主机，不能直接改为公网监听或跨机连接；后端后续配置`ES_ADDRESS=127.0.0.1:9200`。
- 初始化将宿主机`vm.max_map_count`至少设为1048576并写入专用sysctl文件，已有更高值不降低。这是映射数量限制，不代表预先占用物理内存；既有同名sysctl文件不一致时拒绝覆盖。
- 初始化拒绝覆盖`/opt/foodmap-search`、同名容器或目标数据卷。失败后先检查日志，不删除数据卷重试；若配置已准备成功，重试构建／启动直接执行Compose，勿重复初始化。
- 首次安装后仍须创建并核对当前`restaurant-search-v004`结构及别名，再开启应用ES同步和查询；单纯启动ES不等于搜索链路接通。结构以[索引说明](../../../docs/architecture/elasticsearch-schema.md)为准，应用及Runner配置沿用[部署计划](../../../docs/plans/cicd-improvements.md)。
