package com.ginkgo.foodmap.restaurant;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.TotalHitsRelation;
import co.elastic.clients.json.JsonData;

import com.ginkgo.foodmap.restaurant.config.RestaurantSearchQueryProperties;
import com.ginkgo.foodmap.restaurant.dto.RestaurantListQuery;
import com.ginkgo.foodmap.restaurant.search.RestaurantSearchReader;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

/** 不完整搜索不能伪装成空结果；覆盖真实ES成功回归无法稳定制造的超时/部分分片失败。 */
class RestaurantSearchReaderTest {
    @Test
    void singleHanQueryUsesCharacterOrAndDefaultsToScore() {
        var reader =
                new RestaurantSearchReader(
                        mock(ElasticsearchClient.class),
                        new ObjectMapper(),
                        new RestaurantSearchQueryProperties(),
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        var q = new RestaurantListQuery();
        q.setKeyword(" 𠀀 ");
        var request = reader.request(q, false, "");
        assertThat(q.getEffectiveSort()).isEqualTo("score");
        var match = request.query().bool().must().getFirst().bool();
        assertThat(match.should()).hasSize(4);
        assertThat(match.should())
                .allSatisfy(term -> assertThat(term.term().value().stringValue()).isEqualTo("𠀀"));
        assertThat(request.runtimeMappings()).containsKey("queryRating");
        assertThat(request.sort().getFirst().field().field()).isEqualTo("queryRating");
        q.setSort("relevance");
        assertThat(q.getEffectiveSort()).isEqualTo("relevance");
    }

    @Test
    void rejectsTimeoutPartialFailureAndInexactTotals() throws Exception {
        var client = mock(ElasticsearchClient.class);
        var reader =
                new RestaurantSearchReader(
                        client,
                        new ObjectMapper(),
                        new RestaurantSearchQueryProperties(),
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        var q = new RestaurantListQuery();
        q.setKeyword("咖啡");
        q.setSort("price");
        for (int kind = 0; kind < 3; kind++) {
            boolean timeout = kind == 0;
            int failures = kind == 1 ? 1 : 0;
            var relation = kind == 2 ? TotalHitsRelation.Gte : TotalHitsRelation.Eq;
            SearchResponse<JsonData> response =
                    SearchResponse.of(
                            b ->
                                    b.took(1)
                                            .timedOut(timeout)
                                            .shards(
                                                    s ->
                                                            s.total(1)
                                                                    .successful(
                                                                            failures == 0 ? 1 : 0)
                                                                    .failed(failures))
                                            .hits(
                                                    h ->
                                                            h.hits(java.util.List.of())
                                                                    .total(
                                                                            t ->
                                                                                    t.value(0)
                                                                                            .relation(
                                                                                                    relation))));
            when(client.search(any(SearchRequest.class), eq(JsonData.class))).thenReturn(response);
            assertThatThrownBy(() -> reader.search(q, false))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void rejectsWrongVersionAndMissingPageInsteadOfSilentlyDroppingHits() throws Exception {
        var client = mock(ElasticsearchClient.class);
        var reader =
                new RestaurantSearchReader(
                        client,
                        new ObjectMapper(),
                        new RestaurantSearchQueryProperties(),
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        var q = new RestaurantListQuery();
        q.setKeyword("咖啡");
        q.setSort("reviews");
        SearchResponse<JsonData> wrong =
                SearchResponse.of(
                        b ->
                                b.took(1)
                                        .timedOut(false)
                                        .shards(s -> s.total(1).successful(1).failed(0))
                                        .hits(
                                                h ->
                                                        h.total(
                                                                        t ->
                                                                                t.value(1)
                                                                                        .relation(
                                                                                                TotalHitsRelation
                                                                                                        .Eq))
                                                                .hits(
                                                                        hit ->
                                                                                hit.index("test")
                                                                                        .id("101")
                                                                                        .version(2L)
                                                                                        .source(
                                                                                                JsonData
                                                                                                        .fromJson(
                                                                                                                "{\"id\":101,\"projectionVersion\":1}")))));
        when(client.search(any(SearchRequest.class), eq(JsonData.class))).thenReturn(wrong);
        assertThatThrownBy(() -> reader.search(q, false)).isInstanceOf(IllegalStateException.class);
        SearchResponse<JsonData> missing =
                SearchResponse.of(
                        b ->
                                b.took(1)
                                        .timedOut(false)
                                        .shards(s -> s.total(1).successful(1).failed(0))
                                        .hits(
                                                h ->
                                                        h.hits(java.util.List.of())
                                                                .total(
                                                                        t ->
                                                                                t.value(1)
                                                                                        .relation(
                                                                                                TotalHitsRelation
                                                                                                        .Eq))));
        when(client.search(any(SearchRequest.class), eq(JsonData.class))).thenReturn(missing);
        assertThatThrownBy(() -> reader.search(q, false)).isInstanceOf(IllegalStateException.class);
    }

    /** 完整_source直接转换列表，不依赖资料回查；空关键词必须match_all而非空文本检索。 */
    @Test
    void catalogFetchesFullProjectionAndReturnsPublicRecords() throws Exception {
        var client = mock(ElasticsearchClient.class);
        var mapper = new ObjectMapper();
        var reader =
                new RestaurantSearchReader(
                        client,
                        mapper,
                        new RestaurantSearchQueryProperties(),
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        var query = new RestaurantListQuery();
        var request = reader.request(query, false, "");
        assertThat(request.query().bool().must().getFirst().isMatchAll()).isTrue();
        assertThat(request.query().bool().should()).isEmpty();
        assertThat(request.source().filter().includes())
                .contains("name", "coverPhotoId", "ratingScore");
        SearchResponse<JsonData> response =
                SearchResponse.of(
                        b ->
                                b.took(1)
                                        .timedOut(false)
                                        .shards(s -> s.total(1).successful(1).failed(0))
                                        .hits(
                                                h ->
                                                        h.total(
                                                                        t ->
                                                                                t.value(1)
                                                                                        .relation(
                                                                                                TotalHitsRelation
                                                                                                        .Eq))
                                                                .hits(
                                                                        hit ->
                                                                                hit.index("test")
                                                                                        .id("42")
                                                                                        .version(3L)
                                                                                        .source(
                                                                                                JsonData
                                                                                                        .fromJson(
                                                                                                                mapper
                                                                                                                        .writeValueAsString(
                                                                                                                                RestaurantSearchListPolicyTest
                                                                                                                                        .document()))))));
        when(client.search(any(SearchRequest.class), eq(JsonData.class))).thenReturn(response);
        var result = reader.search(query, false);
        assertThat(result.getRecords()).hasSize(1);
        assertThat(result.getRecords().getFirst().getName()).isEqualTo("索引餐厅");
        verify(client).search(any(SearchRequest.class), eq(JsonData.class));
        verifyNoMoreInteractions(client);
    }
}
