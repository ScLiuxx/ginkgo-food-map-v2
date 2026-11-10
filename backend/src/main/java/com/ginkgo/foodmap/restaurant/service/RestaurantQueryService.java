package com.ginkgo.foodmap.restaurant.service;

import com.ginkgo.foodmap.common.exception.BusinessException;
import com.ginkgo.foodmap.restaurant.config.RestaurantSearchQueryProperties;
import com.ginkgo.foodmap.restaurant.dto.RestaurantListQuery;
import com.ginkgo.foodmap.restaurant.dto.RestaurantSearchResult;
import com.ginkgo.foodmap.restaurant.exception.RestaurantErrorCode;
import com.ginkgo.foodmap.restaurant.exception.RestaurantSearchStaleException;
import com.ginkgo.foodmap.restaurant.metrics.RestaurantSearchMetrics;
import com.ginkgo.foodmap.restaurant.search.RestaurantSearchReader;
import com.ginkgo.foodmap.restaurant.vo.RestaurantMapPointsVo;
import com.ginkgo.foodmap.restaurant.vo.RestaurantPageVo;
import com.ginkgo.foodmap.restaurant.vo.RestaurantSearchStatus;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;

import lombok.RequiredArgsConstructor;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.io.IOException;

/** 列表直接读取ES投影，允许同步延迟；地图点位暂保留回查。ES故障整次降级SQL，空结果不降级。 */
@Service
@RequiredArgsConstructor
public class RestaurantQueryService {
    private final RestaurantReadService mysql;
    private final ObjectProvider<RestaurantSearchReader> readers;
    private final RestaurantSearchQueryProperties properties;
    private final CircuitBreaker restaurantEsQueryCircuitBreaker;
    private final MeterRegistry meters;

    /** 列表统一10000条访问窗口，实际总数和可访问页数分开返回。 */
    public RestaurantPageVo list(RestaurantListQuery query) {
        long started = System.nanoTime();
        RestaurantSearchStatus status = null;
        RestaurantPageVo page = null;
        try {
            validate(query, false);
            status = status(query, false);
            page = list(query, status);
            return page;
        } finally {
            RestaurantSearchMetrics.list(meters, query, status, page, started);
        }
    }

    /** 保留原引擎选择和降级流程；外层统一计时，SQL失败也计入最终失败。 */
    private RestaurantPageVo list(RestaurantListQuery query, RestaurantSearchStatus status) {
        RestaurantPageVo result;
        if (status.getEngine().equals("ES")) {
            try {
                var projection = searchEs(query, false);
                result = new RestaurantPageVo();
                result.setRecords(projection.getRecords());
                result.setTotal(projection.getTotal());
                result.setPage(query.getPage());
                result.setPageSize(query.getPageSize());
                result.setTotalPages(
                        projection.getTotal() / query.getPageSize()
                                + (projection.getTotal() % query.getPageSize() == 0 ? 0 : 1));
            } catch (CallNotPermittedException open) {
                fallback(status, "ES_CIRCUIT_OPEN", false);
                result = mysql.list(sqlQuery(query));
            } catch (IOException | RuntimeException unavailable) {
                fallback(status, "ES_UNAVAILABLE", false);
                result = mysql.list(sqlQuery(query));
            }
        } else {
            result = mysql.list(sqlQuery(query));
        }
        if (result != null) {
            result.setSearch(status);
            result.setAccessiblePages(
                    Math.min(result.getTotalPages(), 10000 / query.getPageSize()));
        }
        return result;
    }

    /** 地图保留完整视口及100点上限，不把列表当前页冒充完整点位。 */
    public RestaurantMapPointsVo mapPoints(RestaurantListQuery query) {
        validate(query, true);
        var status = status(query, true);
        RestaurantMapPointsVo result;
        if (status.getEngine().equals("ES")) {
            try {
                result = mysql.searchMap(query, searchEs(query, true));
            } catch (CallNotPermittedException open) {
                fallback(status, "ES_CIRCUIT_OPEN", true);
                result = mysql.mapPoints(sqlQuery(query));
            } catch (RestaurantSearchStaleException changed) {
                fallback(status, "DATA_CHANGED", true);
                result = mysql.mapPoints(sqlQuery(query));
            } catch (IOException | RuntimeException unavailable) {
                fallback(status, "ES_UNAVAILABLE", true);
                result = mysql.mapPoints(sqlQuery(query));
            }
        } else {
            result = mysql.mapPoints(sqlQuery(query));
        }
        if (result != null) {
            result.setSearch(status);
        }
        return result;
    }

    /** ES检查、分析和检索整体计作一次调用；异常先记录再传出，SQL降级不计成功样本。 */
    private RestaurantSearchResult searchEs(RestaurantListQuery query, boolean map)
            throws IOException {
        try {
            return restaurantEsQueryCircuitBreaker.executeCallable(
                    () -> readers.getObject().search(query, map));
        } catch (IOException | RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            // Callable接口允许其他受检异常；统一交由外层完整SQL降级，不吞掉失败样本。
            throw new IllegalStateException("ES查询调用失败", failure);
        }
    }

    private void validate(RestaurantListQuery query, boolean map) {
        if (map && (query.getWest() == null || !query.isBoundsValid())) {
            throw new BusinessException(RestaurantErrorCode.INVALID_INPUT, "地图查询必须提供完整有效视口");
        }
        if (!map && query.getOffset() + query.getPageSize() > 10000) {
            throw new BusinessException(RestaurantErrorCode.INVALID_INPUT, "列表最多访问前10000条，请缩小条件");
        }
    }

    private RestaurantSearchStatus status(RestaurantListQuery query, boolean map) {
        var result = new RestaurantSearchStatus();
        result.setEngine("MYSQL");
        String sort = query.getEffectiveSort();
        result.setEffectiveSort(sort);
        String keyword = query.getKeyword().strip();
        if (map && keyword.isEmpty()) {
            result.setReason("CATALOG");
        } else {
            result.setResultWindow(10000);
            if (query.getLatitude() == null
                    && (sort.equals("distance") || query.getMaxDistanceMeters() != null)) {
                result.setReason("NO_LOCATION");
            } else if (!properties.isEnabled()) {
                result.setReason("DISABLED");
            } else if (readers.getIfAvailable() == null) {
                fallback(result, "ES_UNAVAILABLE", map);
            } else if (map && mysql.hasUnsyncedSearchChanges()) {
                fallback(result, "SYNC_PENDING", map);
            } else {
                result.setEngine("ES");
                result.setReason(keyword.isEmpty() ? "CATALOG" : "SEARCH");
            }
        }
        if (result.getEngine().equals("MYSQL") && sort.equals("relevance")) {
            result.setEffectiveSort("score");
        }
        return result;
    }

    private void fallback(RestaurantSearchStatus status, String reason, boolean map) {
        // reason仅来自固定分支，不记录关键词、商户ID或异常正文，保持指标标签数量有界。
        RestaurantSearchMetrics.fallback(meters, reason, map);
        status.setEngine("MYSQL");
        status.setDegraded(true);
        status.setReason(reason);
        if (status.getEffectiveSort().equals("relevance")) {
            status.setEffectiveSort("score");
        }
    }

    /** SQL不混用ES候选或总数；评分及好评率按约定的一位小数比较，同值以ID破同分。 */
    private RestaurantListQuery sqlQuery(RestaurantListQuery query) {
        var sql = new RestaurantListQuery();
        BeanUtils.copyProperties(query, sql);
        String sort = query.getEffectiveSort();
        sql.setSort(sort.equals("relevance") ? "score" : sort);
        sql.setSearchOrderById(
                !query.getKeyword().strip().isEmpty()
                        && (sort.equals("price")
                                || sort.equals("reviews")
                                || sort.equals("distance")
                                || sort.equals("score")
                                || sort.equals("recommend")));
        return sql;
    }
}
