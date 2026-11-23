package com.foodmap.restaurant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.foodmap.restaurant.entity.RestaurantRankingCandidate;
import com.foodmap.restaurant.policy.RestaurantRankingPolicy;
import com.foodmap.restaurant.policy.RestaurantRankingPolicy.Area;
import com.foodmap.review.dto.RestaurantPeriodReviewStats;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.LongStream;

/** 用可手工核对的计分、同分及球面边界案例验证产品规则，不镜像SQL实现。 */
class RestaurantRankingPolicyTest {
    private final RestaurantRankingPolicy policy = new RestaurantRankingPolicy();
    private final Area area =
            new Area("QINGSHUIHE", new BigDecimal("30.7544"), new BigDecimal("103.9312"), 5000);

    @Test
    void convertsScoresOnceAndUsesConfirmedWeightsAndPrior() {
        var stats = stats(1, 2, "18", "16", "14", "12");
        var result = policy.rank(List.of(candidate(1)), List.of(stats), area).getFirst();
        assertThat(result.tasteMean()).isEqualByComparingTo("4.5");
        // 两人时原始加权分4.25与基准3.5各占一半。
        assertThat(result.sortingScore()).isEqualByComparingTo("3.875");
    }

    @Test
    void qualifiesOneUserByUnroundedWeightedRawScoreInsteadOfTasteOrFinalScore() {
        var result =
                policy.rank(
                        List.of(
                                candidate(1),
                                candidate(2),
                                candidate(3),
                                candidate(4),
                                candidate(5)),
                        List.of(
                                stats(1, 1, "8", "8", "8", "8"),
                                stats(2, 5, "38", "38", "38", "38"),
                                stats(3, 11, "83.5", "83.5", "83.5", "83.5"),
                                stats(4, 1, "8", "0", "0", "0"),
                                stats(5, 1, "7", "10", "10", "10")),
                        area);
        assertThat(result).extracting(value -> value.restaurant().getId()).containsExactly(1L, 5L);
        assertThat(result.getFirst().sortingScore()).isLessThan(new BigDecimal("3.8"));
        assertThat(result.getLast().tasteMean()).isEqualByComparingTo("3.5");
    }

    @Test
    void onePerfectReviewCompetesButDoesNotAutomaticallyEnterTopTwenty() {
        var candidates = LongStream.rangeClosed(1, 21).mapToObj(this::candidate).toList();
        var stats = new java.util.ArrayList<RestaurantPeriodReviewStats>();
        for (long id = 1; id <= 20; id++) {
            stats.add(stats(id, 20, "172", "172", "172", "172"));
        }
        stats.add(stats(21, 1, "10", "10", "10", "10"));
        assertThat(policy.rank(candidates, stats, area))
                .hasSize(20)
                .noneMatch(row -> row.restaurant().getId() == 21);
    }

    @Test
    void exactSameWUsesParticipantsThenNumericRestaurantId() {
        var result =
                policy.rank(
                        List.of(candidate(10), candidate(2), candidate(1)),
                        List.of(
                                stats(1, 2, "20", "20", "20", "20"),
                                stats(10, 4, "40", "20", "40", "40"),
                                stats(2, 4, "40", "20", "40", "40")),
                        area);
        assertThat(result)
                .extracting(value -> value.restaurant().getId())
                .containsExactly(2L, 10L, 1L);
        assertThat(result)
                .allSatisfy(
                        value -> assertThat(value.sortingScore()).isEqualByComparingTo("4.25"));
    }

    @Test
    void roundedEqualScoresDoNotTriggerParticipantOrIdTiebreak() {
        var result =
                policy.rank(
                        List.of(candidate(1), candidate(2)),
                        List.of(
                                stats(1, 3, "27", "24", "21", "18"),
                                stats(2, 3, "27", "24.5", "21", "18")),
                        area);
        assertThat(result).extracting(value -> value.restaurant().getId()).containsExactly(2L, 1L);
        assertThat(result.get(0).tasteMean().setScale(1, java.math.RoundingMode.HALF_UP))
                .isEqualByComparingTo(
                        result.get(1).tasteMean().setScale(1, java.math.RoundingMode.HALF_UP));
        assertThat(result.get(0).sortingScore().setScale(1, java.math.RoundingMode.HALF_UP))
                .isEqualByComparingTo(
                        result.get(1).sortingScore().setScale(1, java.math.RoundingMode.HALF_UP));
    }

    @Test
    void filtersAreaBeforeTakingTopTwentyAndKeepsContiguousOrder() {
        var candidates = LongStream.rangeClosed(1, 25).mapToObj(this::candidate).toList();
        candidates.getFirst().setLatitude(new BigDecimal("31.7544"));
        var stats =
                LongStream.rangeClosed(1, 25)
                        .mapToObj(id -> stats(id, 3, "30", "30", "30", "30"))
                        .toList();
        var result = policy.rank(candidates, stats, area);
        assertThat(result).hasSize(20);
        assertThat(result)
                .extracting(value -> value.restaurant().getId())
                .containsExactlyElementsOf(LongStream.rangeClosed(2, 21).boxed().toList());
    }

    @Test
    void distanceIncludesFiveKilometersAndRejectsOutsideOrInvalidLocations() {
        var equator = new Area("SHAHE", BigDecimal.ZERO, BigDecimal.ZERO, 5000);
        var edge = BigDecimal.valueOf(Math.toDegrees(5000.0 / 6371000));
        assertThat(policy.within(equator, edge, BigDecimal.ZERO)).isTrue();
        assertThat(policy.within(equator, edge.add(new BigDecimal("0.000001")), BigDecimal.ZERO))
                .isFalse();
        assertThat(policy.within(area, area.latitude(), area.longitude())).isTrue();
        assertThat(policy.within(area, null, area.longitude())).isFalse();
        assertThat(policy.within(area, new BigDecimal("91"), area.longitude())).isFalse();
    }

    @Test
    void sameRestaurantMayQualifyForBothOverlappingCampuses() {
        var other = new Area("SHAHE", area.latitude(), area.longitude(), 5000);
        var candidates = List.of(candidate(1));
        var stats = List.of(stats(1, 3, "30", "30", "30", "30"));
        assertThat(policy.rank(candidates, stats, area)).hasSize(1);
        assertThat(policy.rank(candidates, stats, other)).hasSize(1);
    }

    @Test
    void corruptOrDuplicateAggregatesFailInsteadOfPublishingPartialResults() {
        var stat = stats(1, 3, "31", "30", "30", "30");
        assertThatThrownBy(() -> policy.rank(List.of(candidate(1)), List.of(stat), area))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> policy.rank(List.of(candidate(1)), List.of(stat, stat), area))
                .isInstanceOf(IllegalStateException.class);
    }

    private RestaurantRankingCandidate candidate(long id) {
        var result = new RestaurantRankingCandidate();
        result.setId(id);
        result.setName("餐厅" + id);
        result.setLatitude(area.latitude());
        result.setLongitude(area.longitude());
        return result;
    }

    private static RestaurantPeriodReviewStats stats(
            long id, long count, String taste, String value, String service, String ambience) {
        var result = new RestaurantPeriodReviewStats();
        result.setRestaurantId(id);
        result.setParticipantCount(count);
        result.setTasteSum(new BigDecimal(taste));
        result.setValueSum(new BigDecimal(value));
        result.setServiceSum(new BigDecimal(service));
        result.setAmbienceSum(new BigDecimal(ambience));
        return result;
    }
}
