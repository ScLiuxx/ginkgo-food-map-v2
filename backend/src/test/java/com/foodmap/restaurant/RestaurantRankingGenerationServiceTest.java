package com.foodmap.restaurant;

import com.foodmap.restaurant.entity.RestaurantRankingSnapshotEntity;
import com.foodmap.restaurant.service.RestaurantRankingCalculationService;
import com.foodmap.restaurant.service.RestaurantRankingGenerationService;
import com.foodmap.restaurant.service.RestaurantRankingSnapshotPublishService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 验证编排顺序、失败不发布及生成互斥；真实事务提交由MySQL专项覆盖。 */
class RestaurantRankingGenerationServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-11T10:00:00Z");
    private final RestaurantRankingCalculationService calculation = mock(RestaurantRankingCalculationService.class);
    private final RestaurantRankingSnapshotPublishService publication = mock(RestaurantRankingSnapshotPublishService.class);
    private final List<RestaurantRankingSnapshotEntity> computed = List.of(new RestaurantRankingSnapshotEntity());
    private RestaurantRankingGenerationService service;

    @BeforeEach
    void setup() {
        service = new RestaurantRankingGenerationService(calculation, publication, Clock.fixed(NOW, ZoneOffset.UTC));
        when(calculation.calculate(NOW)).thenReturn(computed);
    }

    @Test
    void calculatesThenPublishesTheSameCompleteResult() {
        assertThat(service.generate()).isTrue();
        var order = inOrder(calculation, publication);
        order.verify(calculation).calculate(NOW);
        order.verify(publication).publish(computed);
        order.verifyNoMoreInteractions();
    }

    @Test
    void calculationFailureNeverPublishesAndNextRunCanRecover() {
        when(calculation.calculate(NOW)).thenThrow(new IllegalStateException("isolated failure")).thenReturn(computed);
        assertThatThrownBy(service::generate).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(publication);
        assertThat(service.generate()).isTrue();
        verify(publication).publish(computed);
    }

    @Test
    void publicationFailureReleasesGenerationGuardForNextRun() {
        doThrow(new IllegalStateException("isolated failure")).doNothing().when(publication).publish(computed);
        assertThatThrownBy(service::generate).isInstanceOf(IllegalStateException.class);
        assertThat(service.generate()).isTrue();
        verify(calculation, times(2)).calculate(NOW);
    }

    @Test
    void concurrentGenerationSkipsWithoutQueuingAnotherCalculation() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(calculation.calculate(NOW)).thenAnswer(invocation -> {
            entered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return computed;
        });
        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(service::generate);
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(service.generate()).isFalse();
                verify(calculation).calculate(NOW);
                verifyNoInteractions(publication);
            } finally {
                release.countDown();
            }
            assertThat(first.get(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(service.generate()).isTrue();
    }

    @Test
    void guardRemainsHeldUntilPublicationFinishes() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(publication).publish(any());
        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(service::generate);
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(service.generate()).isFalse();
                verify(calculation).calculate(NOW);
            } finally {
                release.countDown();
            }
            assertThat(first.get(5, TimeUnit.SECONDS)).isTrue();
        }
        doNothing().when(publication).publish(any());
        assertThat(service.generate()).isTrue();
    }

    @Test
    void ambientTransactionIsRejectedBeforeStartingWork() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(service::generate).isInstanceOf(IllegalStateException.class);
            verifyNoInteractions(calculation, publication);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        assertThat(service.generate()).isTrue();
    }
}
