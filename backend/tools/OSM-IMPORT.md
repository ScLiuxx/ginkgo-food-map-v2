# 沙河校区餐厅测试数据

工具仅用于本机`ginkgo_food_map`，读取被Git忽略的`backend/config/application-local.yml`。数据源为OpenStreetMap Overpass JSON；不抓取高德或点评平台，不操作生产，不创建账号或点评。

```powershell
python -m pip install -r backend/tools/requirements-osm.txt
python -m unittest discover -s backend/tools -p test_import_osm_restaurants.py
python backend/tools/import_osm_restaurants.py --input /path/raw-osm.json --report /path/preview.json
# 用户已要求导入后，核对预览再写入；整批事务，同批最多100条，重复运行不追加下一批。
python backend/tools/import_osm_restaurants.py --input /path/raw-osm.json --report /path/result.json --apply
```

- 校区中心来自[OSM relation 19816230](https://www.openstreetmap.org/relation/19816230)的边界框中心（WGS-84：30.6781776,104.0971288），只是选取样本的参考中心，不声称为校门或用户当前位置。
- 接受3公里内具名restaurant、fast_food、food_court；排除显式失效、超长名称、同名近邻和已存在同源记录。按直线距离选择，不把相距较远的同名分店合并。
- 保存来源ID和原始标签到导入报告，库内标签保留来源ID/批次/许可证。原始JSON、查询语句、预览和执行清单位于仓库外`D:/eat/.codex-backups/shahe-restaurants/`；不将业务数据库配置及凭据输出或提交。
- WGS-84通过[eviltransform](https://github.com/googollee/eviltransform)转换为GCJ-02，保留原坐标。开源算法结果仅用于联调，真实高德落点及入口仍需人工核验。非node几何使用边界框中心，可能不是餐厅入口。
- 地址缺失为NULL，人均UNKNOWN、金额NULL，不生成评分或点评。fast_food映射快餐，其余归其他，不根据名字编造菜系。OPERATING仅满足现有结构的未标记停业状态，不代表实时营业；统一标记“OSM测试数据”“未经实地核实”。
- 运行前数据库须迁移至V12；导入SQL不再写入source_type或coordinate_system。统一保存转换后的GCJ-02坐标，未知创建者保持为空；OSM来源通过标签和导入清单记录。
- 导入前后比较存量餐厅/标签以及其他表指纹；失败整批回滚。重复运行包含软删除的本批记录，不复活、不覆盖。回退只能针对清单内新ID，在确认没有新增点评等引用后另行处理，不执行全库清空。
- © OpenStreetMap contributors，数据遵循[ODbL-1.0](https://www.openstreetmap.org/copyright)。保留署名及许可链接；对外分发该数据或衍生数据库时按许可履行相应义务。这里导入的是开放POI记录，经营状态和地址不保证最新。
- 项目现有目录“距校区”仍以清水河参考点计算；报告内distanceToShaheMeters才是距本次沙河参考中心的距离，本工具不改变产品校区设置。
