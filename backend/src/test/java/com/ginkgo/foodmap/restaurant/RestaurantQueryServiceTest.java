package com.ginkgo.foodmap.restaurant;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ginkgo.foodmap.common.exception.BusinessException;
import com.ginkgo.foodmap.restaurant.config.RestaurantSearchCircuitBreakerConfiguration;
import com.ginkgo.foodmap.restaurant.config.RestaurantSearchQueryProperties;
import com.ginkgo.foodmap.restaurant.dto.*;
import com.ginkgo.foodmap.restaurant.exception.RestaurantSearchStaleException;
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

    @BeforeEach
    void prepare() {
        properties.setEnabled(true);
        when(provider.getIfAvailable()).thenReturn(reader);
        when(provider.getObject()).thenReturn(reader);
        when(mysql.list(any())).thenAnswer(a -> page(19));
        when(mysql.mapPoints(any())).thenReturn(new RestaurantMapPointsVo());
        var meters = new SimpleMeterRegistry();
        var breaker =
                new RestaurantSearchCircuitBreakerConfiguration(properties)
                        .restaurantEsQueryCircuitBreaker(meters);
        service = new RestaurantQueryService(mysql, provider, properties, breaker, meters);
    }

    @Test
    void defaultAndExplicitSortsAreDifferentAndSingleHanIncludesSupplementaryCharacters() {
        for (String keyword : List.of("")) {
            var q = query(keyword);
            var result = service.list(q);
            assertThat(result.getSearch().getEngine()).isEqualTo("MYSQL");
            assertThat(result.getSearch().getEffectiveSort()).isEqualTo("score");
            assertThat(q.getSort()).isEqualTo("auto");
        }
        verifyNoInteractions(reader);
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

    @Test
    void versionChangeFallsBackWithNewTotalAndBasicSort() throws Exception {
        var q = query("清水河咖啡");
        var hits = new RestaurantSearchResult();
        hits.setTotal(99);
        when(reader.search(q, false)).thenReturn(hits);
        when(mysql.searchList(q, hits)).thenThrow(new RestaurantSearchStaleException());
        var result = service.list(q);
        assertThat(result.getTotal()).isEqualTo(19);
        assertThat(result.getSearch().getReason()).isEqualTo("DATA_CHANGED");
        assertThat(result.getSearch().getEffectiveSort()).isEqualTo("score");
        assertThat(q.getSort()).isEqualTo("auto");
    }

    @Test
    void pendingChangesSkipEsIncludingEmptyOrMissingNewCandidates() {
        when(mysql.hasUnsyncedSearchChanges()).thenReturn(true);
        assertThat(service.list(query("新咖啡")).getSearch().getReason()).isEqualTo("SYNC_PENDING");
        verifyNoInteractions(reader);
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
        assertThat(service.list(q)).isNotNull();
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
