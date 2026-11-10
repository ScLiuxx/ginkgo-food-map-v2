package com.ginkgo.foodmap.restaurant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.FieldCapsResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.indices.AnalyzeResponse;
import co.elastic.clients.elasticsearch.indices.ElasticsearchIndicesClient;
import co.elastic.clients.json.JsonData;

import com.ginkgo.foodmap.restaurant.config.RestaurantSearchQueryProperties;
import com.ginkgo.foodmap.restaurant.dto.RestaurantListQuery;
import com.ginkgo.foodmap.restaurant.metrics.RestaurantSearchMetrics;
import com.ginkgo.foodmap.restaurant.search.RestaurantSearchReader;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import java.net.SocketTimeoutException;
import java.util.List;
import java.util.function.Function;

/** 验证真实Reader调用边界和有界标签；失败及地图路径不能制造成功或列表请求样本。 */
class RestaurantSearchMetricsTest {
    private final ElasticsearchClient client = mock(ElasticsearchClient.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final RestaurantSearchReader reader =
            new RestaurantSearchReader(
                    client, new ObjectMapper(), new RestaurantSearchQueryProperties(), meters);

    /** 普通浏览只调用search一次，返回0条仍是成功。 */
    @Test
    void browseRecordsOneSearchAndSuccess() throws Exception {
        when(client.search(any(SearchRequest.class), eq(JsonData.class))).thenReturn(empty());
        reader.search(new RestaurantListQuery(), false);
        assertThat(
                        meters.get("restaurant.search.es.duration")
                                .tags("outcome", "success", "reason", "none")
                                .timer()
                                .count())
                .isEqualTo(1);
        assertThat(
                        meters.get("restaurant.search.es.requests")
                                .tag("operation", "search")
                                .counter()
                                .count())
                .isEqualTo(1);
        assertThat(meters.get("restaurant.search.es.requests.per.query").summary().totalAmount())
                .isEqualTo(1);
    }

    /** 相关度检索包含analyze和search两次；不把一个业务阶段误认为一个网络请求。 */
    @Test
    @SuppressWarnings("unchecked")
    void relevanceRecordsAnalyzeAndSearch() throws Exception {
        var indices = mock(ElasticsearchIndicesClient.class);
        when(client.indices()).thenReturn(indices);
        when(indices.analyze(any(Function.class)))
                .thenReturn(
                        AnalyzeResponse.of(
                                b ->
                                        b.tokens(
                                                t ->
                                                        t.token("coffee")
                                                                .startOffset(0)
                                                                .endOffset(6)
                                                                .position(0)
                                                                .type("word"))));
        when(client.search(any(SearchRequest.class), eq(JsonData.class))).thenReturn(empty());
        var query = new RestaurantListQuery();
        query.setKeyword("coffee");
        reader.search(query, false);
        assertThat(
                        meters.get("restaurant.search.es.requests")
                                .tag("operation", "analyze")
                                .counter()
                                .count())
                .isEqualTo(1);
        assertThat(
                        meters.get("restaurant.search.es.requests")
                                .tag("operation", "search")
                                .counter()
                                .count())
                .isEqualTo(1);
        assertThat(meters.get("restaurant.search.es.requests.per.query").summary().totalAmount())
                .isEqualTo(2);
    }

    /** 单字能力检查失败后不能虚构search调用；请求文本不能成为标签。 */
    @Test
    @SuppressWarnings("unchecked")
    void failedCapabilityCheckCountsOnlyAttemptedOperation() throws Exception {
        when(client.fieldCaps(any(Function.class)))
                .thenReturn(
                        FieldCapsResponse.of(b -> b.indices(List.of()).fields(java.util.Map.of())));
        var query = new RestaurantListQuery();
        query.setKeyword("茶");
        assertThatThrownBy(() -> reader.search(query, false))
                .isInstanceOf(IllegalStateException.class);
        assertThat(
                        meters.get("restaurant.search.es.requests")
                                .tag("operation", "field_caps")
                                .counter()
                                .count())
                .isEqualTo(1);
        assertThat(
                        meters.find("restaurant.search.es.requests")
                                .tag("operation", "search")
                                .counter())
                .isNull();
        assertThat(
                        meters.get("restaurant.search.es.duration")
                                .tags("outcome", "error", "reason", "invalid_response")
                                .timer()
                                .count())
                .isEqualTo(1);
        assertThat(meters.getMeters())
                .allSatisfy(
                        meter ->
                                assertThat(meter.getId().getTags().toString()).doesNotContain("茶"));
    }

    /** 超时失败保留请求样本与异常，地图不进入首期列表指标。 */
    @Test
    void timeoutAndMapAreNotSuccessfulListSamples() throws Exception {
        when(client.search(any(SearchRequest.class), eq(JsonData.class)))
                .thenThrow(new SocketTimeoutException());
        assertThatThrownBy(() -> reader.search(new RestaurantListQuery(), false))
                .isInstanceOf(SocketTimeoutException.class);
        assertThat(
                        meters.get("restaurant.search.es.duration")
                                .tags("reason", "timeout", "outcome", "error")
                                .timer()
                                .count())
                .isEqualTo(1);
        assertThatThrownBy(() -> reader.search(new RestaurantListQuery(), true))
                .isInstanceOf(SocketTimeoutException.class);
        assertThat(meters.get("restaurant.search.es.requests").counter().count()).isEqualTo(1);
        assertThat(meters.get("restaurant.search.es.requests.per.query").summary().count())
                .isEqualTo(1);
    }

    /** 注册指标抛错时仍返回原来的检索结果，不把观测故障变成业务故障。 */
    @Test
    void registryFailureDoesNotChangeSearchResult() throws Exception {
        var unavailable = mock(MeterRegistry.class);
        when(unavailable.counter(anyString(), any(String[].class)))
                .thenThrow(new IllegalStateException("registry"));
        when(unavailable.config()).thenThrow(new IllegalStateException("registry"));
        var observed =
                new RestaurantSearchReader(
                        client,
                        new ObjectMapper(),
                        new RestaurantSearchQueryProperties(),
                        unavailable);
        when(client.search(any(SearchRequest.class), eq(JsonData.class))).thenReturn(empty());
        assertThat(observed.search(new RestaurantListQuery(), false).getTotal()).isZero();
        RestaurantSearchMetrics.fallback(unavailable, "ES_UNAVAILABLE", false);
    }

    private static SearchResponse<JsonData> empty() {
        return SearchResponse.of(
                b ->
                        b.took(1)
                                .timedOut(false)
                                .shards(s -> s.total(1).successful(1).failed(0))
                                .hits(
                                        h ->
                                                h.total(
                                                                t ->
                                                                        t.value(0)
                                                                                .relation(
                                                                                        co.elastic
                                                                                                .clients
                                                                                                .elasticsearch
                                                                                                .core
                                                                                                .search
                                                                                                .TotalHitsRelation
                                                                                                .Eq))
                                                        .hits(List.of())));
    }
}
