package com.ginkgo.foodmap.restaurant;

import com.ginkgo.foodmap.restaurant.config.RestaurantSearchCircuitBreakerConfiguration;
import com.ginkgo.foodmap.restaurant.config.RestaurantSearchQueryProperties;
import com.ginkgo.foodmap.restaurant.dto.RestaurantListQuery;
import com.ginkgo.foodmap.restaurant.dto.RestaurantSearchResult;
import com.ginkgo.foodmap.restaurant.exception.RestaurantSearchStaleException;
import com.ginkgo.foodmap.restaurant.search.RestaurantSearchReader;
import com.ginkgo.foodmap.restaurant.service.RestaurantQueryService;
import com.ginkgo.foodmap.restaurant.service.RestaurantReadService;
import com.ginkgo.foodmap.restaurant.vo.RestaurantMapPointsVo;
import com.ginkgo.foodmap.restaurant.vo.RestaurantPageVo;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 使用真实Resilience4j与可控时钟验证状态机，不通过线程睡眠碰运气。 */
class RestaurantSearchCircuitBreakerTest {
    private final RestaurantReadService mysql = mock(RestaurantReadService.class);
    private final RestaurantSearchReader reader = mock(RestaurantSearchReader.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<RestaurantSearchReader> readers = mock(ObjectProvider.class);
    private final RestaurantSearchQueryProperties properties = new RestaurantSearchQueryProperties();
    private final MutableClock clock = new MutableClock();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private CircuitBreaker breaker;
    private RestaurantQueryService queries;

    @BeforeEach void prepare() throws Exception {
        properties.setEnabled(true);
        when(readers.getIfAvailable()).thenReturn(reader);when(readers.getObject()).thenReturn(reader);
        var configured = new RestaurantSearchCircuitBreakerConfiguration(properties).restaurantEsQueryCircuitBreaker(meters);
        breaker = CircuitBreaker.of("test-es", CircuitBreakerConfig.from(configured.getCircuitBreakerConfig()).clock(clock).build());
        queries = new RestaurantQueryService(mysql, readers, properties, breaker, meters);
        when(reader.search(any(), anyBoolean())).thenReturn(new RestaurantSearchResult());
        when(mysql.searchList(any(), any())).thenReturn(page(0));
        when(mysql.searchMap(any(), any())).thenReturn(new RestaurantMapPointsVo());
        when(mysql.list(any())).thenReturn(page(19));
        when(mysql.mapPoints(any())).thenReturn(new RestaurantMapPointsVo());
    }

    @Test void minimumSampleSizeAndExactFiftyPercentThresholdAreRespected() throws Exception {
        var q=query();
        for(int i=0;i<5;i++)assertThat(queries.list(q).getSearch().getEngine()).isEqualTo("ES");
        when(reader.search(any(),anyBoolean())).thenThrow(new SocketTimeoutException());
        for(int i=0;i<4;i++)assertThat(queries.list(q).getSearch().getReason()).isEqualTo("ES_UNAVAILABLE");
        assertThat(breaker.getMetrics().getNumberOfBufferedCalls()).isEqualTo(9);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        queries.list(q);
        assertThat(breaker.getMetrics().getFailureRate()).isEqualTo(50);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(queries.mapPoints(q).getSearch().getReason()).isEqualTo("ES_CIRCUIT_OPEN");
        verify(reader,times(10)).search(any(),anyBoolean());
        assertThat(meters.get("restaurant.search.fallbacks").tag("reason","ES_UNAVAILABLE").counter().count()).isEqualTo(5);
    }

    @Test void recentTwentyCallsEvictOldSuccesses() throws Exception {
        for(int i=0;i<20;i++)queries.list(query());
        when(reader.search(any(),anyBoolean())).thenThrow(new SocketTimeoutException());
        for(int i=0;i<9;i++)queries.list(query());
        assertThat(breaker.getMetrics().getFailureRate()).isEqualTo(45);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        queries.list(query());
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test void completeOutageStillNeedsTenSamplesAndDoesNotCountSqlSuccessAsEsSuccess() throws Exception {
        when(reader.search(any(),anyBoolean())).thenThrow(new SocketTimeoutException());
        for(int i=0;i<9;i++)assertThat(queries.list(query()).getTotal()).isEqualTo(19);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        queries.list(query());
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(breaker.getMetrics().getNumberOfSuccessfulCalls()).isZero();
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(10);
    }

    @Test void halfOpenPermitsOnlyOneRealRequestAndSuccessfulProbeClosesCircuit() throws Exception {
        openWithFailures();
        clock.advanceMillis(9999);
        assertThat(queries.list(query()).getSearch().getReason()).isEqualTo("ES_CIRCUIT_OPEN");
        clock.advanceMillis(2);
        // 等待到期不主动探测；真实请求触发后才进入半开。
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        var entered = new CountDownLatch(1);var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new SocketTimeoutException();
            return new RestaurantSearchResult();
        }).when(reader).search(any(),anyBoolean());
        try(var executor=Executors.newSingleThreadExecutor()) {
            var probe=executor.submit(()->queries.list(query()));
            try {
                assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();
                assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
                var rejected=queries.mapPoints(query());
                assertThat(rejected.getSearch().getReason()).isEqualTo("ES_CIRCUIT_OPEN");
                verify(reader,times(11)).search(any(),anyBoolean());
            } finally {release.countDown();}
            assertThat(probe.get(3,TimeUnit.SECONDS).getSearch().getEngine()).isEqualTo("ES");
        }
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(queries.mapPoints(query()).getSearch().getEngine()).isEqualTo("ES");
    }

    @Test void failedProbeReopensAndWaitsAnotherTenSeconds() throws Exception {
        openWithFailures();clock.advanceMillis(10001);
        assertThat(queries.list(query()).getSearch().getReason()).isEqualTo("ES_UNAVAILABLE");
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(queries.mapPoints(query()).getSearch().getReason()).isEqualTo("ES_CIRCUIT_OPEN");
        clock.advanceMillis(9999);
        assertThat(queries.list(query()).getSearch().getReason()).isEqualTo("ES_CIRCUIT_OPEN");
        clock.advanceMillis(2);
        doReturn(new RestaurantSearchResult()).when(reader).search(any(),anyBoolean());
        assertThat(queries.mapPoints(query()).getSearch().getEngine()).isEqualTo("ES");
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test void staleVersionsAndMysqlReadFailuresNeverTripEsBreaker() {
        when(mysql.searchList(any(),any())).thenThrow(new RestaurantSearchStaleException());
        for(int i=0;i<12;i++)assertThat(queries.list(query()).getSearch().getReason()).isEqualTo("DATA_CHANGED");
        doThrow(new IllegalStateException("mysql unavailable")).when(mysql).searchList(any(),any());
        for(int i=0;i<12;i++)queries.list(query());
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isZero();
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test void syncPendingCatalogAndDisabledRequestsDoNotEnterEsSampleWindow() {
        when(mysql.hasUnsyncedSearchChanges()).thenReturn(true);
        for(int i=0;i<12;i++)assertThat(queries.list(query()).getSearch().getReason()).isEqualTo("SYNC_PENDING");
        var catalog=query();catalog.setKeyword("");queries.list(catalog);
        properties.setEnabled(false);queries.list(query());
        assertThat(breaker.getMetrics().getNumberOfBufferedCalls()).isZero();
        verifyNoInteractions(reader);
    }

    @Test void productionConfigurationRegistersBoundedCountersAndStateGauge() throws Exception {
        var registry=new SimpleMeterRegistry();
        var registered=new RestaurantSearchCircuitBreakerConfiguration(properties).restaurantEsQueryCircuitBreaker(registry);
        for(int i=0;i<10;i++) {
            var target=registered;
            assertThatThrownBy(()->target.executeCallable(()->{throw new SocketTimeoutException();})).isInstanceOf(SocketTimeoutException.class);
        }
        var target=registered;
        assertThatThrownBy(()->target.executeCallable(()->null)).isInstanceOf(CallNotPermittedException.class);
        assertThat(registry.get("restaurant.search.es.calls").tag("outcome","failure").counter().count()).isEqualTo(10);
        assertThat(registry.get("restaurant.search.es.calls").tag("outcome","rejected").counter().count()).isEqualTo(1);
        assertThat(registry.get("restaurant.search.es.circuit.state").gauge().value()).isEqualTo(1);
        registered.transitionToHalfOpenState();registered.executeCallable(()->null);
        assertThat(registry.get("restaurant.search.es.calls").tag("outcome","success").counter().count()).isEqualTo(1);
        assertThat(registry.get("restaurant.search.es.circuit.state").gauge().value()).isZero();
    }

    private void openWithFailures() throws Exception {
        when(reader.search(any(),anyBoolean())).thenThrow(new SocketTimeoutException());
        for(int i=0;i<10;i++)queries.list(query());
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }
    private static RestaurantPageVo page(long total) {var p=new RestaurantPageVo();p.setTotal(total);return p;}
    private static RestaurantListQuery query() {
        var q=new RestaurantListQuery();q.setKeyword("咖啡");q.setWest(new BigDecimal("103"));q.setEast(new BigDecimal("105"));
        q.setSouth(new BigDecimal("30"));q.setNorth(new BigDecimal("32"));return q;
    }
    /** 只推进熔断等待所需的墙钟，测试不修改系统时钟。 */
    private static final class MutableClock extends Clock {
        private final AtomicLong millis=new AtomicLong(Instant.parse("2026-11-05T00:00:00Z").toEpochMilli());
        void advanceMillis(long amount) {millis.addAndGet(amount);}
        @Override public ZoneId getZone() {return ZoneOffset.UTC;}
        @Override public Clock withZone(ZoneId zone) {if(!zone.equals(ZoneOffset.UTC))throw new IllegalArgumentException();return this;}
        @Override public Instant instant() {return Instant.ofEpochMilli(millis.get());}
    }
}
