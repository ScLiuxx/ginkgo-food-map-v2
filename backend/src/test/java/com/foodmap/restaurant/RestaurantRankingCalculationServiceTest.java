package com.foodmap.restaurant;

import com.foodmap.restaurant.config.RestaurantRankingProperties;
import com.foodmap.restaurant.mapper.RestaurantRankingCandidateMapper;
import com.foodmap.restaurant.policy.RestaurantRankingPolicy;
import com.foodmap.restaurant.service.RestaurantRankingCalculationService;
import com.foodmap.restaurant.support.RestaurantRankingKey;
import com.foodmap.restaurant.support.RestaurantRankingSnapshotCodec;
import com.foodmap.review.service.ReviewRankingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 验证计算只返回完整四榜、统一时间和错误边界；真实SQL与RR一致性另由隔离MySQL验证。 */
class RestaurantRankingCalculationServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-11T08:00:00.123456789Z");
    private final RestaurantRankingCandidateMapper candidates = mock(RestaurantRankingCandidateMapper.class);
    private final ReviewRankingService reviews = mock(ReviewRankingService.class);
    private final RestaurantRankingProperties properties = new RestaurantRankingProperties();
    private final JsonMapper json = JsonMapper.builder().build();
    private RestaurantRankingCalculationService service;

    @BeforeEach
    void setup() {
        when(candidates.selectCandidates()).thenReturn(List.of());
        when(reviews.aggregate(any(), any())).thenReturn(List.of());
        service = new RestaurantRankingCalculationService(candidates, reviews, new RestaurantRankingPolicy(), properties,
                json, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void noReviewsProduceFourCalculatedEmptyBoardsWithOneMicrosecondCutoffAndRules() {
        var result = service.calculate(NOW);
        var cutoff = NOW.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var codec = new RestaurantRankingSnapshotCodec(json);
        assertThat(result).hasSize(4);
        for (int i = 0; i < result.size(); i++) {
            var row = result.get(i);
            assertThat(row.getWindowEndAt()).isEqualTo(cutoff);
            assertThat(row.getGeneratedAt()).isEqualTo(cutoff);
            var view = codec.view(row, RestaurantRankingKey.ALL.get(i), NOW, properties.getMaxSnapshotAge());
            assertThat(view.getStatus()).isEqualTo("READY");
            assertThat(view.getItems()).isEmpty();
            var rules = json.readTree(row.getRulesSnapshot());
            assertThat(rules.get("c").decimalValue()).isEqualByComparingTo("3.5");
            assertThat(rules.get("m").intValue()).isEqualTo(2);
            assertThat(rules.get("sourceScoreScale").intValue()).isEqualTo(10);
            assertThat(rules.get("campus").get("coordinateSystem").stringValue()).isEqualTo("GCJ02");
        }
    }

    @Test
    void nullFutureOrStaleCutoffFailsBeforeDatabaseAccess() {
        for (var cutoff : new Instant[] {null, NOW.plusNanos(1), NOW.minusSeconds(86401)}) {
            assertThatThrownBy(() -> service.calculate(cutoff)).isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(candidates, reviews);
    }

    @Test
    void aggregationFailureDoesNotBecomeFourSuccessfulEmptyBoards() {
        when(reviews.aggregate(any(), any())).thenThrow(new IllegalStateException("isolated failure"));
        assertThatThrownBy(() -> service.calculate(NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void invalidCampusFailsBeforeQueryingRatherThanReturningAnEmptyBoard() {
        properties.getShahe().setRadiusMeters(Double.NaN);
        assertThatThrownBy(() -> service.calculate(NOW)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(candidates, reviews);
    }
}
