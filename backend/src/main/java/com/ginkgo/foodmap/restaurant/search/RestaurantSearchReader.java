package com.ginkgo.foodmap.restaurant.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.search.TotalHitsRelation;
import co.elastic.clients.json.JsonData;
import com.ginkgo.foodmap.restaurant.config.RestaurantSearchQueryProperties;
import com.ginkgo.foodmap.restaurant.dto.RestaurantListQuery;
import com.ginkgo.foodmap.restaurant.dto.RestaurantSearchResult;
import com.ginkgo.foodmap.restaurant.policy.RestaurantSearchQueryPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.StringReader;
import java.util.*;

/**
 * ES适配器一次完成匹配、硬筛选、全局排序、精确总数和有界分页，不读取业务数据库。
 * 地理边界从_source原始GCJ-02坐标计算，避免geo_point量化及默认地球半径改变既有SQL边界。
 * 首版优先明确语义，runtime字段的成本留待隔离性能对照，不宣称已获得地理查询加速。
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.search.elasticsearch.enabled", havingValue = "true")
public class RestaurantSearchReader {
    private final ElasticsearchClient client;
    private final ObjectMapper json;
    private final RestaurantSearchQueryProperties properties;
    /** 只为有文字搜索意义的四字段建立辅助倒排索引，数值及地理字段不参与。 */
    private static final List<String> CHARACTER_FIELDS = List.of("name.chars", "address.chars", "categoryName.chars", "tags.chars");

    /** 固定超时、不接受部分分片成功；失败交给应用层整次降级，不把异常解释成空结果。 */
    public RestaurantSearchResult search(RestaurantListQuery query, boolean map) throws IOException {
        String target = properties.getIndexAlias();
        if (RestaurantSearchQueryPolicy.isSingleHan(query.getKeyword())) {
            // term查询遇到未映射字段会静默返回0；先确认四字段均可检索，并固定本次物理索引。
            // 固定目标避免校验之后别名切回旧结构时，将索引未就绪误解释成业务空结果。
            var capabilities = client.fieldCaps(b -> b.index(properties.getIndexAlias()).fields(CHARACTER_FIELDS));
            if (capabilities.indices().size() != 1 || CHARACTER_FIELDS.stream().anyMatch(field ->
                    !capabilities.fields().containsKey(field)
                    || !capabilities.fields().get(field).containsKey("text")
                    || !capabilities.fields().get(field).get("text").searchable()))
                throw new IllegalStateException("单字搜索索引未就绪");
            target = capabilities.indices().getFirst();
        }
        String exact = "";
        if ("relevance".equals(query.getEffectiveSort())) {
            var analyzed = client.indices().analyze(b -> b.index(properties.getIndexAlias())
                    .normalizer("restaurant_name_exact").text(query.getKeyword().strip()));
            if (analyzed.tokens().size() != 1) throw new IllegalStateException("店名规范化结果异常");
            exact = analyzed.tokens().getFirst().token();
        }
        var response = client.search(request(query, map, exact, target), JsonData.class);
        var total = response.hits().total();
        if (response.timedOut() || response.shards().failed().longValue() != 0 || total == null
                || total.relation() != TotalHitsRelation.Eq)
            throw new IllegalStateException("搜索未完整完成");
        var result = new RestaurantSearchResult();
        result.setTotal(total.value());
        var hits = new ArrayList<RestaurantSearchResult.Hit>();
        var seen = new HashSet<Long>();
        for (var hit : response.hits().hits()) {
            if (hit.source() == null) throw new IllegalStateException("搜索身份缺失");
            var source = json.readTree(hit.source().toJson().toString());
            long id = source.path("id").asLong(0), version = source.path("projectionVersion").asLong(0);
            if (id <= 0 || version <= 0 || !Long.toString(id).equals(hit.id())
                    || !Objects.equals(hit.version(), version) || !seen.add(id))
                throw new IllegalStateException("搜索身份或版本错误");
            var item = new RestaurantSearchResult.Hit();item.setId(id);item.setProjectionVersion(version);hits.add(item);
        }
        int size = map ? 100 : query.getPageSize();
        long offset = map ? 0 : query.getOffset();
        if (hits.size() != Math.min(size, Math.max(0, total.value() - offset)))
            throw new IllegalStateException("搜索页不完整");
        result.setHits(List.copyOf(hits));
        return result;
    }

    /** JSON仅由固定字段及结构化参数组装；用户输入不作为查询语法、脚本或字段名执行。 */
    public SearchRequest request(RestaurantListQuery query, boolean map, String exact) {
        return request(query, map, exact, properties.getIndexAlias());
    }

    private SearchRequest request(RestaurantListQuery query, boolean map, String exact, String target) {
        var filters = new ArrayList<Object>();
        filters.add(term("deleted", false));
        if (!query.getCategory().isEmpty()) filters.add(term("categoryCode", query.getCategory()));
        if (!query.getRestaurantType().isEmpty()) filters.add(term("restaurantType", query.getRestaurantType()));
        if (query.getDiningMode().equals("dineIn")) filters.add(term("supportsDineIn", true));
        if (query.getDiningMode().equals("delivery")) filters.add(term("supportsDelivery", true));
        if (query.getHideClosed()) filters.add(term("businessStatus", "OPERATING"));
        if (query.getMaxPrice() != null) {
            filters.add(Map.of("bool", Map.of("must_not", List.of(term("priceKind", "UNKNOWN")))));
            filters.add(range("pricePerPerson", Map.of("lte", query.getMaxPrice())));
        }
        var runtime = new LinkedHashMap<String, Object>();
        // 对现有评分字段生成有界整数排序键，不改变原始统计或商户投影数值。
        // 评分8.3分 -> 83；好评率92.3%（原比例0.923）-> 923，正数四舍五入，同键只按ID。
        if (query.getEffectiveSort().equals("score")) runtime.put("queryRating", roundedRating("ratingScore", 10));
        if (query.getEffectiveSort().equals("recommend")) runtime.put("queryRecommend", roundedRating("recommendRate", 1000));
        if (query.getWest() != null) {
            runtime.put("queryLat", coordinate("lat"));runtime.put("queryLon", coordinate("lon"));
            filters.add(range("queryLat", Map.of("gte", query.getSouth(), "lte", query.getNorth())));
            filters.add(range("queryLon", Map.of("gte", query.getWest(), "lte", query.getEast())));
        }
        if (query.getLatitude() != null && (query.getMaxDistanceMeters() != null
                || query.getEffectiveSort().equals("distance"))) {
            runtime.put("queryDistance", Map.of("type", "double", "script", Map.of("source", DISTANCE,
                    "params", Map.of("lat", query.getLatitude(), "lon", query.getLongitude()))));
        }
        if (query.getMaxDistanceMeters() != null)
            filters.add(range("queryDistance", Map.of("lte", query.getMaxDistanceMeters())));
        Object matching = Map.of("multi_match", Map.of("query", query.getKeyword().strip(),
                "type", "cross_fields", "operator", "and", "analyzer", "ik_smart",
                "fields", List.of("name^6", "categoryName^3", "tags^2", "address")));
        boolean singleHan = RestaurantSearchQueryPolicy.isSingleHan(query.getKeyword());
        if (singleHan) {
            // 直接以原字查辅助字段，四类文字任一包含即满足，不再依赖IK是否产生独立字词项。
            matching = Map.of("bool", Map.of("should", CHARACTER_FIELDS.stream()
                    .map(field -> term(field, query.getKeyword().strip())).toList(), "minimum_should_match", 1));
        }
        var bool = new LinkedHashMap<String, Object>();
        bool.put("must", List.of(matching));bool.put("filter", filters);
        if (!singleHan) bool.put("should", List.of(Map.of("match_phrase", Map.of("name",
                Map.of("query", query.getKeyword().strip(), "analyzer", "ik_smart", "boost", 12)))));
        var sorts = new ArrayList<Object>();
        switch (query.getEffectiveSort()) {
            case "relevance" -> {
                // 精确店名用独立排序层保证优先，不依赖任意boost压过所有BM25分数。
                sorts.add(Map.of("_script", Map.of("type", "number", "order", "desc", "script",
                        Map.of("source", "doc['name.exact'].size()!=0 && doc['name.exact'].value.equals(params.name) ? 1 : 0",
                                "params", Map.of("name", exact)))));
                sorts.add(Map.of("_score", Map.of("order", "desc")));
            }
            case "price" -> sorts.add(fieldSort("pricePerPerson", "asc"));
            case "reviews" -> sorts.add(fieldSort("reviewCount", "desc"));
            case "distance" -> sorts.add(fieldSort("queryDistance", "asc"));
            case "score" -> sorts.add(fieldSort("queryRating", "desc"));
            case "recommend" -> sorts.add(fieldSort("queryRecommend", "desc"));
            default -> throw new IllegalArgumentException("未知搜索排序");
        }
        sorts.add(Map.of("id", "asc"));
        var body = new LinkedHashMap<String, Object>();
        body.put("query", Map.of("bool", bool));body.put("sort", sorts);
        body.put("track_total_hits", true);body.put("version", true);
        body.put("_source", List.of("id", "projectionVersion"));
        body.put("from", map ? 0 : query.getOffset());body.put("size", map ? 100 : query.getPageSize());
        body.put("timeout", "800ms");
        if (!runtime.isEmpty()) body.put("runtime_mappings", runtime);
        return new SearchRequest.Builder().index(target).allowPartialSearchResults(false)
                .withJson(new StringReader(json.writeValueAsString(body))).build();
    }

    private static Object term(String field, Object value) { return Map.of("term", Map.of(field, value)); }
    private static Object range(String field, Object values) { return Map.of("range", Map.of(field, values)); }
    private static Object fieldSort(String field, String direction) {
        return Map.of(field, Map.of("order", direction, "missing", "_last"));
    }
    private static Object coordinate(String axis) {
        return Map.of("type", "double", "script", Map.of("source",
                "if (params._source.needsLocation == false && params._source.location != null) emit((double)params._source.location[params.axis]);",
                "params", Map.of("axis", axis)));
    }

    /** 未计分时不emit，排序按missing末尾处理；真实0仍emit(0)，不会混同未知。 */
    private static Object roundedRating(String field, int factor) {
        return Map.of("type", "long", "script", Map.of("source",
                "if (doc[params.field].size()!=0) emit(Math.round(doc[params.field].value * params.factor));",
                "params", Map.of("field", field, "factor", factor)));
    }

    /** 与现行MySQL共用6371000米半径和haversine计算；未知位置不emit，范围比较不会把它当0。 */
    private static final String DISTANCE = """
        if (params._source.needsLocation == false && params._source.location != null) {
          double lat = params._source.location.lat; double lon = params._source.location.lon;
          double a = Math.pow(Math.sin(Math.toRadians(lat - (double)params.lat) / 2), 2)
            + Math.cos(Math.toRadians((double)params.lat)) * Math.cos(Math.toRadians(lat))
            * Math.pow(Math.sin(Math.toRadians(lon - (double)params.lon) / 2), 2);
          emit(6371000 * 2 * Math.asin(Math.sqrt(Math.min(1.0, Math.max(0.0, a)))));
        }
        """;
}
