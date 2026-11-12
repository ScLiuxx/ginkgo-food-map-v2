package com.ginkgo.foodmap.restaurant;

import com.ginkgo.foodmap.restaurant.entity.RestaurantRankingCandidate;
import com.ginkgo.foodmap.restaurant.policy.RestaurantRankingPolicy;
import com.ginkgo.foodmap.restaurant.policy.RestaurantRankingPolicy.Area;
import com.ginkgo.foodmap.review.dto.RestaurantPeriodReviewStats;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 用可手工核对的计分、同分及球面边界案例验证产品规则，不镜像SQL实现。 */
class RestaurantRankingPolicyTest {
    private final RestaurantRankingPolicy policy = new RestaurantRankingPolicy();
    private final Area area = new Area("QINGSHUIHE", new BigDecimal("30.7544"), new BigDecimal("103.9312"), 5000);

    @Test
    void convertsScoresOnceAndUsesConfirmedWeightsAndPrior() {
        var stats = stats(1, 3, "27", "24", "21", "18");
        var result = policy.rank(List.of(candidate(1)), List.of(stats), area).getFirst();
        assertThat(result.tasteMean()).isEqualByComparingTo("4.5");
        // R=4.25，W=(3*4.25+5*3.5)/8=3.78125；旧累计综合分及推荐、收藏不参与。
        assertThat(result.numerator()).isEqualByComparingTo("30.25");
        assertThat(result.sortingScore()).isEqualByComparingTo("3.78125");
    }

    @Test
    void participantAndUnroundedTasteThresholdsAreIndependent() {
        var result = policy.rank(List.of(candidate(1), candidate(2), candidate(3)),
                List.of(stats(1, 2, "20", "20", "20", "20"),
                        // 87.5/2/11=3.977…，页面虽显示4.0，原始门槛仍不满足。
                        stats(2, 11, "87.5", "110", "110", "110"),
                        stats(3, 3, "24", "0", "0", "0")), area);
        assertThat(result).extracting(value -> value.restaurant().getId()).containsExactly(3L);
        assertThat(result.getFirst().tasteMean()).isEqualByComparingTo("4");
    }

    @Test
    void exactSameWUsesParticipantsThenNumericRestaurantId() {
        var result = policy.rank(List.of(candidate(10), candidate(2), candidate(1)),
                List.of(stats(1, 3, "30", "30", "30", "30"),
                        stats(10, 5, "50", "25", "50", "50"),
                        stats(2, 5, "50", "25", "50", "50")), area);
        assertThat(result).extracting(value -> value.restaurant().getId()).containsExactly(2L, 10L, 1L);
        assertThat(result).allSatisfy(value -> assertThat(value.sortingScore()).isEqualByComparingTo("4.0625"));
    }

    @Test
    void roundedEqualScoresDoNotTriggerParticipantOrIdTiebreak() {
        var result = policy.rank(List.of(candidate(1), candidate(2)),
                List.of(stats(1, 3, "27", "24", "21", "18"),
                        stats(2, 3, "27", "24.5", "21", "18")), area);
        assertThat(result).extracting(value -> value.restaurant().getId()).containsExactly(2L, 1L);
        assertThat(result.get(0).tasteMean().setScale(1, java.math.RoundingMode.HALF_UP))
                .isEqualByComparingTo(result.get(1).tasteMean().setScale(1, java.math.RoundingMode.HALF_UP));
        assertThat(result.get(0).sortingScore().setScale(1, java.math.RoundingMode.HALF_UP))
                .isEqualByComparingTo(result.get(1).sortingScore().setScale(1, java.math.RoundingMode.HALF_UP));
    }

    @Test
    void filtersAreaBeforeTakingTopTwentyAndKeepsContiguousOrder() {
        var candidates = LongStream.rangeClosed(1, 25).mapToObj(this::candidate).toList();
        candidates.getFirst().setLatitude(new BigDecimal("31.7544"));
        var stats = LongStream.rangeClosed(1, 25).mapToObj(id -> stats(id, 3, "30", "30", "30", "30")).toList();
        var result = policy.rank(candidates, stats, area);
        assertThat(result).hasSize(20);
        assertThat(result).extracting(value -> value.restaurant().getId())
                .containsExactlyElementsOf(LongStream.rangeClosed(2, 21).boxed().toList());
    }

    @Test
    void distanceIncludesFiveKilometersAndRejectsOutsideOrInvalidLocations() {
        var equator = new Area("SHAHE", BigDecimal.ZERO, BigDecimal.ZERO, 5000);
        var edge = BigDecimal.valueOf(Math.toDegrees(5000.0 / 6371000));
        assertThat(policy.within(equator, edge, BigDecimal.ZERO)).isTrue();
        assertThat(policy.within(equator, edge.add(new BigDecimal("0.000001")), BigDecimal.ZERO)).isFalse();
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

    private static RestaurantPeriodReviewStats stats(long id, long count, String taste, String value, String service, String ambience) {
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
