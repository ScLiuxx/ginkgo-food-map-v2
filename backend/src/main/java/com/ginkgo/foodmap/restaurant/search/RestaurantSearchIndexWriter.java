package com.ginkgo.foodmap.restaurant.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.VersionType;
import co.elastic.clients.elasticsearch.core.GetRequest;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import co.elastic.clients.json.JsonData;
import com.ginkgo.foodmap.restaurant.config.RestaurantSearchSyncProperties;
import com.ginkgo.foodmap.restaurant.dto.RestaurantSearchDocument;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 写现有别名中的完整文档或墓碑；不建索引、不主动refresh，使用严格外部版本防乱序。 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.search.sync.enabled", havingValue = "true")
public class RestaurantSearchIndexWriter {
    private final ElasticsearchClient client;
    private final ObjectMapper json;
    private final RestaurantSearchSyncProperties properties;

    /** 同版本冲突只有正文一致才成功；更高版本已经覆盖本次修改时可结束旧任务。 */
    public void write(RestaurantSearchDocument document) throws IOException {
        if (document.getId() == null || document.getId() <= 0 || document.getProjectionVersion() == null
                || document.getProjectionVersion() <= 0 || document.getDeleted() == null)
            throw new IllegalArgumentException("搜索文档身份、版本或删除状态错误");
        String payload = json.writeValueAsString(document);
        try {
            var response = client.index(new IndexRequest.Builder<JsonData>()
                    .index(properties.getIndexAlias()).id(document.documentId()).requireAlias(true)
                    .version(document.getProjectionVersion()).versionType(VersionType.External)
                    .document(JsonData.fromJson(payload)).build());
            if (response.version() != document.getProjectionVersion())
                throw new IllegalStateException("ES确认版本不匹配");
        } catch (ElasticsearchException conflict) {
            if (conflict.status() != 409) throw conflict;
            var stored = client.get(new GetRequest.Builder().index(properties.getIndexAlias())
                    .id(document.documentId()).build(), JsonData.class);
            if (!stored.found() || stored.source() == null || stored.version() == null) throw conflict;
            Object source = json.readValue(stored.source().toJson().toString(), Object.class);
            if (!(source instanceof Map<?, ?> fields)
                    || !sameJson(fields.get("id"), document.getId())
                    || !sameJson(fields.get("projectionVersion"), stored.version()))
                throw new IllegalStateException("ES正文与元数据身份或版本不一致");
            if (stored.version() > document.getProjectionVersion()) return;
            if (stored.version().equals(document.getProjectionVersion())
                    && sameJson(source, json.readValue(payload, Object.class))) return;
            throw new IllegalStateException("ES同版本正文冲突，不能确认任务");
        }
    }

    /** JSON数字按数值比较，允许ES返回1与1.0的等价形式；标签数组仍严格保序、字段集合必须一致。 */
    private boolean sameJson(Object left, Object right) {
        if (left instanceof Number a && right instanceof Number b)
            return new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString())) == 0;
        if (left instanceof Map<?, ?> a && right instanceof Map<?, ?> b)
            return a.keySet().equals(b.keySet()) && a.keySet().stream().allMatch(key -> sameJson(a.get(key), b.get(key)));
        if (left instanceof List<?> a && right instanceof List<?> b) {
            if (a.size() != b.size()) return false;
            for (int i = 0; i < a.size(); i++) if (!sameJson(a.get(i), b.get(i))) return false;
            return true;
        }
        return Objects.equals(left, right);
    }
}
