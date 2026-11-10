package com.ginkgo.foodmap.restaurant;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ginkgo.foodmap.common.exception.BusinessException;
import com.ginkgo.foodmap.restaurant.config.RestaurantSearchCircuitBreakerConfiguration;
import com.ginkgo.foodmap.restaurant.config.RestaurantSearchQueryProperties;
import com.ginkgo.foodmap.restaurant.dto.*;
import com.ginkgo.foodmap.restaurant.search.RestaurantSearchReader;
import com.ginkgo.foodmap.restaurant.service.*;
import com.ginkgo.foodmap.restaurant.vo.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;

/** 引擎策略及故障边界：整次SQL降级不能保留ES旧总数，也不能修改调用方筛选对象。 */
class RestaurantQueryServiceTest {
    private final RestaurantReadService mysql = mock(RestaurantReadService.class);
    private final RestaurantSearchReader reader = mock(RestaurantSearchReader.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<RestaurantSearchReader> provider = mock(ObjectProvider.class);

    private final RestaurantSearchQueryProperties properties =
            new RestaurantSearchQueryProperties();
    private RestaurantQueryService service;
    private SimpleMeterRegistry meters;

    @BeforeEach
    void prepare() {
        properties.setEnabled(true);
        when(provider.getIfAvailable()).thenReturn(reader);
        when(provider.getObject()).thenReturn(reader);
        when(mysql.list(any())).thenAnswer(a -> page(19));
        when(mysql.mapPoints(any())).thenReturn(new RestaurantMapPointsVo());
        meters = new SimpleMeterRegistry();
        var breaker =
                new RestaurantSearchCircuitBreakerConfiguration(properties)
                        .restaurantEsQueryCircuitBreaker(meters);
        service = new RestaurantQueryService(mysql, provider, properties, breaker, meters);
    }

    /** 无关键词列表由ES承担，成功路径不访问MySQL，包括同步任务检查。 */
    @Test
    void catalogReadsEsProjectionWithoutMysql() throws Exception {
        var q = query("");
        var projection = new RestaurantSearchResult();
        var row = new RestaurantVo();
        row.setId("42");
        row.setName("索引中的餐厅");
        projection.setRecords(List.of(row));
        projection.setTotal(1);
        when(reader.search(q, false)).thenReturn(projection);
        var result = service.list(q);
        assertThat(result.getRecords()).containsExactly(row);
        assertThat(result.getSearch().getEngine()).isEqualTo("ES");
        assertThat(result.getSearch().getReason()).isEqualTo("CATALOG");
        assertThat(result.getTotalPages()).isEqualTo(1);
        assertThat(result.getPageSize()).isEqualTo(8);
        verifyNoInteractions(mysql);
    }

    /** 单字不再选择SQL引擎，补充平面汉字也走同一路径；默认综合分且显式排序仍有效。 */
    @Test
    void singleHanUsesEsWithoutChangingDefaultScore() throws Exception {
        for (String word : List.of("茶", "𠀀")) {
            var q = query(word);
            var hits = new RestaurantSearchResult();
            hits.setHits(List.of());
            when(reader.search(q, false)).thenReturn(hits);
            when(mysql.searchList(q, hits)).thenReturn(page(0));
            assertThat(q.getEffectiveSort()).isEqualTo("score");
            var result = service.list(q);
            assertThat(result.getSearch().getEngine()).isEqualTo("ES");
            assertThat(result.getSearch().getEffectiveSort()).isEqualTo("score");
        }
        verify(mysql, never()).list(any());
    }

    @Test
    void scoreAndRecommendationKeepEsMatchingForBothEntrypoints() throws Exception {
        for (String sort : List.of("score", "recommend")) {
            var q = query("清水河咖啡");
            q.setSort(sort);
            bounds(q);
            var hits = new RestaurantSearchResult();
            hits.setHits(List.of());
            when(reader.search(q, false)).thenReturn(hits);
            when(reader.search(q, true)).thenReturn(hits);
            when(mysql.searchList(q, hits)).thenReturn(page(0));
            when(mysql.searchMap(q, hits)).thenReturn(new RestaurantMapPointsVo());
            var list = service.list(q);
            var map = service.mapPoints(q);
            assertThat(list.getSearch().getEngine()).isEqualTo("ES");
            assertThat(list.getSearch().getEffectiveSort()).isEqualTo(sort);
            assertThat(map.getSearch().getEngine()).isEqualTo("ES");
        }
        verify(mysql, never()).list(any());
        verify(mysql, never()).mapPoints(any());
    }

    @Test
    void listAndMapShareEngineAndAllFilters() throws Exception {
        var q = query("清水河咖啡");
        q.setMaxPrice(new BigDecimal("30"));
        q.setDiningMode("dineIn");
        bounds(q);
        var hits = new RestaurantSearchResult();
        hits.setHits(List.of());
        when(reader.search(q, false)).thenReturn(hits);
        when(reader.search(q, true)).thenReturn(hits);
        when(mysql.searchList(q, hits)).thenReturn(page(0));
        when(mysql.searchMap(q, hits)).thenReturn(new RestaurantMapPointsVo());
        assertThat(service.list(q).getSearch().getEffectiveSort()).isEqualTo("relevance");
        assertThat(service.mapPoints(q).getSearch().getEngine()).isEqualTo("ES");
        verify(reader).search(q, false);
        verify(reader).search(q, true);
        verify(mysql, never()).list(any());
    }

    @Test
    void offlineFallsBackWholeRequestAndKeepsManualSort() throws Exception {
        var q = query("咖啡");
        q.setSort("price");
        bounds(q);
        when(reader.search(any(), anyBoolean())).thenThrow(new IOException("connection refused"));
        var result = service.list(q);
        var map = service.mapPoints(q);
        assertThat(result.getTotal()).isEqualTo(19);
        assertThat(result.getSearch().isDegraded()).isTrue();
        assertThat(result.getSearch().getEffectiveSort()).isEqualTo("price");
        assertThat(map.getSearch()).isEqualTo(result.getSearch());
        verify(mysql)
                .list(
                        argThat(
                                sql ->
                                        sql.getKeyword().equals("咖啡")
                                                && sql.getSort().equals("price")
                                                && sql.isSearchOrderById()));
        verify(mysql, never()).searchList(any(), any());
    }

    /** 积压不触发列表回查，明确接受短暂过时投影。 */
    @Test
    void pendingChangesDoNotMoveListBackToMysql() throws Exception {
        var q = query("新咖啡");
        var projection = new RestaurantSearchResult();
        projection.setTotal(99);
        when(reader.search(q, false)).thenReturn(projection);
        assertThat(service.list(q).getTotal()).isEqualTo(99);
        assertThat(service.list(q).getSearch().getEngine()).isEqualTo("ES");
        verifyNoInteractions(mysql);
    }

    @Test
    void disabledAndMissingClientDoNotRequireAnEsServer() {
        properties.setEnabled(false);
        assertThat(service.list(query("咖啡")).getSearch().isDegraded()).isFalse();
        properties.setEnabled(true);
        when(provider.getIfAvailable()).thenReturn(null);
        assertThat(service.list(query("咖啡")).getSearch().getReason()).isEqualTo("ES_UNAVAILABLE");
        verifyNoInteractions(reader);
    }

    @Test
    void emptyEsResultDoesNotFallBack() throws Exception {
        var q = query("不存在咖啡");
        var hits = new RestaurantSearchResult();
        hits.setHits(List.of());
        when(reader.search(q, false)).thenReturn(hits);
        when(mysql.searchList(q, hits)).thenReturn(page(0));
        assertThat(service.list(q).getTotal()).isZero();
        verify(mysql, never()).list(any());
    }

    @Test
    void resultWindowIsStableAcrossEnabledAndDisabledModes() {
        var q = query("咖啡");
        q.setPage(1251);
        assertThatThrownBy(() -> service.list(q)).isInstanceOf(BusinessException.class);
        properties.setEnabled(false);
        assertThatThrownBy(() -> service.list(q)).isInstanceOf(BusinessException.class);
        q.setKeyword("");
        assertThatThrownBy(() -> service.list(q)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(reader);
    }

    /** ES成功与零命中各只计一次；不按当前页为空判断没有结果。 */
    @Test
    void recordsEsSuccessAndEmptyResults() throws Exception {
        var q = query("咖啡");
        when(reader.search(q, false)).thenReturn(new RestaurantSearchResult());
        service.list(q);
        assertThat(
                        meters.get("restaurant.search.requests")
                                .tags("source", "es", "route", "es", "outcome", "success")
                                .counter()
                                .count())
                .isEqualTo(1);
        assertThat(meters.get("restaurant.search.duration").timer().count()).isEqualTo(1);
        assertThat(meters.get("restaurant.search.results").tag("empty", "true").counter().count())
                .isEqualTo(1);
        assertThat(meters.find("restaurant.search.fallbacks").counter()).isNull();
    }

    /** 主动SQL路径不算降级，完整匹配有数据但当前页为空不算零结果。 */
    @Test
    void recordsDisabledSqlWithoutFallback() {
        properties.setEnabled(false);
        service.list(query(""));
        assertThat(
                        meters.get("restaurant.search.requests")
                                .tags("query_type", "browse", "source", "sql", "route", "sql")
                                .counter()
                                .count())
                .isEqualTo(1);
        assertThat(meters.get("restaurant.search.results").tag("empty", "false").counter().count())
                .isEqualTo(1);
        assertThat(meters.find("restaurant.search.fallbacks").counter()).isNull();
    }

    /** ES失败后SQL成功仍为成功；SQL也失败时保留降级次数并记最终失败。 */
    @Test
    void recordsFallbackAndFinalFailureSeparately() throws Exception {
        when(reader.search(any(), eq(false))).thenThrow(new IOException("test"));
        service.list(query("咖啡"));
        when(mysql.list(any())).thenThrow(new IllegalStateException("database unavailable"));
        assertThatThrownBy(() -> service.list(query("咖啡")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(
                        meters.get("restaurant.search.requests")
                                .tags("source", "sql", "outcome", "success", "route", "es")
                                .counter()
                                .count())
                .isEqualTo(1);
        assertThat(
                        meters.get("restaurant.search.requests")
                                .tags("source", "none", "outcome", "error", "route", "es")
                                .counter()
                                .count())
                .isEqualTo(1);
        assertThat(
                        meters.get("restaurant.search.fallbacks")
                                .tags("scope", "list", "reason", "ES_UNAVAILABLE")
                                .counter()
                                .count())
                .isEqualTo(2);
        assertThat(meters.get("restaurant.search.results").counter().count()).isEqualTo(1);
    }

    /** ES客户端不存在同样属于计划走ES后降级；参数校验失败不归因于ES故障。 */
    @Test
    void recordsMissingClientAndValidationWithoutWrongAttribution() {
        when(provider.getIfAvailable()).thenReturn(null);
        service.list(query("咖啡"));
        var invalid = query("咖啡");
        invalid.setPage(1251);
        assertThatThrownBy(() -> service.list(invalid)).isInstanceOf(BusinessException.class);
        assertThat(
                        meters.get("restaurant.search.requests")
                                .tags("route", "es", "source", "sql")
                                .counter()
                                .count())
                .isEqualTo(1);
        assertThat(
                        meters.get("restaurant.search.requests")
                                .tags("route", "none", "outcome", "error")
                                .counter()
                                .count())
                .isEqualTo(1);
        assertThat(meters.get("restaurant.search.fallbacks").counter().count()).isEqualTo(1);
    }

    /** 熔断拒绝仍计ES路线和降级，但不调用Reader；地图不混入列表总量。 */
    @Test
    void recordsOpenCircuitWithoutCallingReader() {
        var breaker =
                new RestaurantSearchCircuitBreakerConfiguration(properties)
                        .restaurantEsQueryCircuitBreaker(meters);
        breaker.transitionToOpenState();
        service = new RestaurantQueryService(mysql, provider, properties, breaker, meters);
        service.list(query("咖啡"));
        assertThat(
                        meters.get("restaurant.search.fallbacks")
                                .tags("scope", "list", "reason", "ES_CIRCUIT_OPEN")
                                .counter()
                                .count())
                .isEqualTo(1);
        assertThat(
                        meters.get("restaurant.search.requests")
                                .tags("route", "es", "source", "sql")
                                .counter()
                                .count())
                .isEqualTo(1);
        var map = query("");
        bounds(map);
        service.mapPoints(map);
        assertThat(meters.get("restaurant.search.requests").counter().count()).isEqualTo(1);
        verifyNoInteractions(reader);
    }

    private static RestaurantListQuery query(String keyword) {
        var q = new RestaurantListQuery();
        q.setKeyword(keyword);
        return q;
    }

    private static RestaurantPageVo page(long total) {
        var p = new RestaurantPageVo();
        p.setTotal(total);
        p.setTotalPages((total + 7) / 8);
        return p;
    }

    private static void bounds(RestaurantListQuery q) {
        q.setWest(new BigDecimal("103"));
        q.setEast(new BigDecimal("105"));
        q.setSouth(new BigDecimal("30"));
        q.setNorth(new BigDecimal("32"));
    }
}
