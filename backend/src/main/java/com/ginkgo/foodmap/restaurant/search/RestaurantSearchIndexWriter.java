package com.ginkgo.foodmap.restaurant.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.VersionType;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.GetRequest;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.json.JsonData;
import co.elastic.clients.json.JsonpUtils;

import com.ginkgo.foodmap.restaurant.config.RestaurantSearchSyncProperties;
import com.ginkgo.foodmap.restaurant.dto.RestaurantSearchDocument;
import com.ginkgo.foodmap.restaurant.metrics.RestaurantSearchSyncMetrics;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
    private final RestaurantSearchSyncMetrics metrics;

    /** 单文档入口也走同一Bulk路径，供内部工具和重放校验复用，避免维护两套写入语义。 */
    public void write(RestaurantSearchDocument document) throws IOException {
        var failure = writeBatch(List.of(document)).get(document.getId()).failure();
        if (failure instanceof IOException io) {
            throw io;
        }
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure != null) {
            throw new IllegalStateException("搜索写入失败", failure);
        }
    }

    /** 返回每家餐厅的独立结果；响应成功不代表全部item成功，超时结果未知时保留安全重放。 */
    public Map<Long, WriteResult> writeBatch(List<RestaurantSearchDocument> documents) {
        if (documents.size() > 100) {
            throw new IllegalArgumentException("Bulk每批最多100家餐厅");
        }
        var identities = new HashSet<Long>();
        for (var document : documents) {
            if (document == null
                    || document.getId() == null
                    || document.getId() <= 0
                    || !identities.add(document.getId())) {
                throw new IllegalArgumentException("Bulk餐厅身份缺失或重复");
            }
        }
        Map<Long, WriteResult> results = new LinkedHashMap<>();
        List<Prepared> chunk = new ArrayList<>();
        long bytes = 0;
        for (var document : documents) {
            Prepared prepared;
            try {
                if (document.getProjectionVersion() == null
                        || document.getProjectionVersion() <= 0
                        || document.getDeleted() == null) {
                    throw new IllegalArgumentException("搜索文档版本或删除状态错误");
                }
                String payload = json.writeValueAsString(document);
                var operation =
                        BulkOperation.of(
                                b ->
                                        b.index(
                                                i ->
                                                        i.index(properties.getIndexAlias())
                                                                .id(document.documentId())
                                                                .version(
                                                                        document
                                                                                .getProjectionVersion())
                                                                .versionType(VersionType.External)
                                                                .document(
                                                                        JsonData.fromJson(
                                                                                payload))));
                // 复用SDK的NDJSON行和当前传输mapper计数，包含元数据、UTF-8正文及换行。
                // 不按字符数或压缩后大小切批；单批最多100份文档，计数的临时字符串有界。
                long size = 0;
                var lines = operation._serializables();
                while (lines.hasNext()) {
                    size +=
                            JsonpUtils.toJsonString(lines.next(), client._jsonpMapper())
                                            .getBytes(StandardCharsets.UTF_8)
                                            .length
                                    + 1L;
                }
                if (size > properties.getBulkMaxBytes()) {
                    throw new IllegalArgumentException("单文档超过Bulk大小上限");
                }
                prepared = new Prepared(document, payload, operation, size);
            } catch (Exception failure) {
                results.put(document.getId(), new WriteResult(failure));
                continue;
            }
            if (!chunk.isEmpty() && bytes + prepared.bytes() > properties.getBulkMaxBytes()) {
                flush(chunk, bytes, results);
                chunk.clear();
                bytes = 0;
            }
            chunk.add(prepared);
            bytes += prepared.bytes();
        }
        if (!chunk.isEmpty()) {
            flush(chunk, bytes, results);
        }
        return results;
    }

    private void flush(List<Prepared> chunk, long bytes, Map<Long, WriteResult> results) {
        var request =
                new BulkRequest.Builder()
                        .requireAlias(true)
                        .operations(chunk.stream().map(Prepared::operation).toList())
                        .build();
        long started = System.nanoTime();
        BulkResponse response;
        try {
            response = client.bulk(request);
            if (response.items().size() != chunk.size()) {
                throw new IllegalStateException("Bulk响应条数不匹配，不能确认任务");
            }
            metrics.bulkRequest(true, chunk.size(), bytes, started);
        } catch (Exception failure) {
            metrics.bulkRequest(false, chunk.size(), bytes, started);
            for (var prepared : chunk) {
                results.put(prepared.document().getId(), new WriteResult(failure));
                metrics.bulkItem(false);
            }
            return;
        }
        for (int position = 0; position < chunk.size(); position++) {
            var prepared = chunk.get(position);
            var item = response.items().get(position);
            Exception failure = null;
            try {
                if (!prepared.document().documentId().equals(item.id())) {
                    throw new IllegalStateException("Bulk响应身份不匹配");
                }
                if (item.status() == 409) {
                    metrics.versionConflict();
                    verifyConflict(prepared.document(), prepared.payload());
                } else if (item.error() != null || (item.status() != 200 && item.status() != 201)) {
                    if (item.status() == 429) {
                        metrics.bulkRejectedItem();
                    }
                    // 不保存ES原始错误正文，避免任务表/日志泄露文档内容；保留状态码便于归类。
                    throw new IllegalStateException("Bulk item失败，status=" + item.status());
                } else if (!Objects.equals(
                        item.version(), prepared.document().getProjectionVersion())) {
                    throw new IllegalStateException("ES确认版本不匹配");
                }
            } catch (Exception itemFailure) {
                failure = itemFailure;
            }
            results.put(prepared.document().getId(), new WriteResult(failure));
            metrics.bulkItem(failure == null);
        }
    }

    /** 同版本严格比正文；更新版本必须核对正文身份与元数据，再允许结束旧任务。 */
    private void verifyConflict(RestaurantSearchDocument document, String payload)
            throws IOException {
        var stored =
                client.get(
                        new GetRequest.Builder()
                                .index(properties.getIndexAlias())
                                .id(document.documentId())
                                .build(),
                        JsonData.class);
        if (!stored.found() || stored.source() == null || stored.version() == null) {
            throw new IllegalStateException("冲突文档不存在或元数据不完整");
        }
        Object source = json.readValue(stored.source().toJson().toString(), Object.class);
        if (!(source instanceof Map<?, ?> fields)
                || !sameJson(fields.get("id"), document.getId())
                || !sameJson(fields.get("projectionVersion"), stored.version())) {
            throw new IllegalStateException("ES正文与元数据身份或版本不一致");
        }
        if (stored.version() > document.getProjectionVersion()) {
            return;
        }
        if (stored.version().equals(document.getProjectionVersion())
                && sameJson(source, json.readValue(payload, Object.class))) {
            return;
        }
        throw new IllegalStateException("ES同版本正文冲突，不能确认任务");
    }

    /** failure为空才允许确认该餐厅本批任务；失败仅在内存传递，持久化仍只存异常类型。 */
    public record WriteResult(Exception failure) {}

    private record Prepared(
            RestaurantSearchDocument document,
            String payload,
            BulkOperation operation,
            long bytes) {}

    /** JSON数字按数值比较，允许ES返回1与1.0的等价形式；标签数组仍严格保序、字段集合必须一致。 */
    private boolean sameJson(Object left, Object right) {
        if (left instanceof Number a && right instanceof Number b) {
            return new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString())) == 0;
        }
        if (left instanceof Map<?, ?> a && right instanceof Map<?, ?> b) {
            return a.keySet().equals(b.keySet())
                    && a.keySet().stream().allMatch(key -> sameJson(a.get(key), b.get(key)));
        }
        if (left instanceof List<?> a && right instanceof List<?> b) {
            if (a.size() != b.size()) {
                return false;
            }
            for (int i = 0; i < a.size(); i++) {
                if (!sameJson(a.get(i), b.get(i))) {
                    return false;
                }
            }
            return true;
        }
        return Objects.equals(left, right);
    }
}
