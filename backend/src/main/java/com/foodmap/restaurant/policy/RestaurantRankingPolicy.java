package com.foodmap.restaurant.policy;

import com.foodmap.restaurant.entity.RestaurantRankingCandidate;
import com.foodmap.review.dto.RestaurantPeriodReviewStats;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

/** 已确认的首版近期口碑规则；精确和参与计算，不复用累计均分或页面舍入值。 */
@Component
public class RestaurantRankingPolicy {
    public static final BigDecimal C = new BigDecimal("3.5");
    public static final int M = 5;
    public static final int MIN_PARTICIPANTS = 1;
    public static final int LIMIT = 20;
    public static final BigDecimal MIN_RAW_SCORE = new BigDecimal("3.8");
    public static final BigDecimal TASTE_WEIGHT = new BigDecimal("0.70");
    public static final BigDecimal VALUE_WEIGHT = new BigDecimal("0.15");
    public static final BigDecimal SERVICE_WEIGHT = new BigDecimal("0.10");
    public static final BigDecimal AMBIENCE_WEIGHT = new BigDecimal("0.05");
    private static final BigDecimal TWO = BigDecimal.valueOf(2);

    /** 校区配置先复制为不可变对象，整轮四榜使用同一份配置；GCJ-02与商户坐标一致。 */
    public record Area(
            String campusCode, BigDecimal latitude, BigDecimal longitude, double radiusMeters) {
        public Area {
            if (!("QINGSHUIHE".equals(campusCode) || "SHAHE".equals(campusCode))
                    || latitude == null
                    || latitude.abs().compareTo(BigDecimal.valueOf(90)) > 0
                    || longitude == null
                    || longitude.abs().compareTo(BigDecimal.valueOf(180)) > 0
                    || !Double.isFinite(radiusMeters)
                    || radiusMeters <= 0) {
                throw new IllegalArgumentException("榜单校区中心或半径无效");
            }
        }
    }

    /** 五分制四维和用于核对；W以分子/分母精确比较，展示均值与诊断分另行舍入。 */
    public record RankedRestaurant(
            RestaurantRankingCandidate restaurant,
            long participantCount,
            BigDecimal tasteSum,
            BigDecimal valueSum,
            BigDecimal serviceSum,
            BigDecimal ambienceSum,
            BigDecimal numerator,
            BigDecimal denominator) {
        public BigDecimal tasteMean() {
            return tasteSum.divide(BigDecimal.valueOf(participantCount), 16, RoundingMode.HALF_UP);
        }

        public BigDecimal sortingScore() {
            return numerator.divide(denominator, 16, RoundingMode.HALF_UP);
        }
    }

    /** 先范围与资格、再全量排序、最后取20；重叠校区可独立纳入同一家餐厅。 */
    public List<RankedRestaurant> rank(
            List<RestaurantRankingCandidate> candidates,
            List<RestaurantPeriodReviewStats> stats,
            Area area) {
        var byRestaurant = new HashMap<Long, RestaurantPeriodReviewStats>();
        for (var stat : stats) {
            if (byRestaurant.put(stat.getRestaurantId(), stat) != null) {
                throw new IllegalStateException("近期统计不应出现重复餐厅");
            }
        }
        var ranked = new ArrayList<RankedRestaurant>();
        for (var candidate : candidates) {
            var stat = byRestaurant.get(candidate.getId());
            if (stat == null
                    || stat.getParticipantCount() < MIN_PARTICIPANTS
                    || !within(area, candidate.getLatitude(), candidate.getLongitude())) {
                continue;
            }
            validateSums(stat);
            var n = BigDecimal.valueOf(stat.getParticipantCount());
            // 原始十分制只换算一次；资格使用四维加权原始和，不使用展示分或人数平滑后的W。
            var taste = stat.getTasteSum().divide(TWO);
            var value = stat.getValueSum().divide(TWO);
            var service = stat.getServiceSum().divide(TWO);
            var ambience = stat.getAmbienceSum().divide(TWO);
            var weightedSum =
                    taste.multiply(TASTE_WEIGHT)
                            .add(value.multiply(VALUE_WEIGHT))
                            .add(service.multiply(SERVICE_WEIGHT))
                            .add(ambience.multiply(AMBIENCE_WEIGHT));
            if (weightedSum.compareTo(MIN_RAW_SCORE.multiply(n)) <= 0) {
                continue;
            }
            ranked.add(
                    new RankedRestaurant(
                            candidate,
                            stat.getParticipantCount(),
                            taste,
                            value,
                            service,
                            ambience,
                            weightedSum.add(C.multiply(BigDecimal.valueOf(M))),
                            n.add(BigDecimal.valueOf(M))));
        }
        // 两个有理分数交叉相乘，差距再小也不因固定小数精度误判同分。
        Comparator<RankedRestaurant> order =
                (left, right) ->
                        right.numerator()
                                .multiply(left.denominator())
                                .compareTo(left.numerator().multiply(right.denominator()));
        order =
                order.thenComparing(
                                Comparator.comparingLong(RankedRestaurant::participantCount)
                                        .reversed())
                        .thenComparingLong(item -> item.restaurant().getId());
        ranked.sort(order);
        return List.copyOf(ranked.subList(0, Math.min(LIMIT, ranked.size())));
    }

    /** 与现有目录采用同一6371000米半径的Haversine直线距离，包含半径边界，不是步行距离。 */
    public boolean within(Area area, BigDecimal latitude, BigDecimal longitude) {
        if (latitude == null
                || longitude == null
                || latitude.abs().compareTo(BigDecimal.valueOf(90)) > 0
                || longitude.abs().compareTo(BigDecimal.valueOf(180)) > 0) {
            return false;
        }
        double lat1 = Math.toRadians(area.latitude().doubleValue());
        double lat2 = Math.toRadians(latitude.doubleValue());
        double dLat = lat2 - lat1;
        double dLon = Math.toRadians(longitude.subtract(area.longitude()).doubleValue());
        double h =
                Math.pow(Math.sin(dLat / 2), 2)
                        + Math.cos(lat1) * Math.cos(lat2) * Math.pow(Math.sin(dLon / 2), 2);
        double meters = 6371000 * 2 * Math.asin(Math.sqrt(Math.max(0, Math.min(1, h))));
        return meters <= area.radiusMeters();
    }

    private static void validateSums(RestaurantPeriodReviewStats stats) {
        var max = BigDecimal.valueOf(stats.getParticipantCount()).multiply(BigDecimal.TEN);
        for (var sum :
                new BigDecimal[] {
                    stats.getTasteSum(),
                    stats.getValueSum(),
                    stats.getServiceSum(),
                    stats.getAmbienceSum()
                }) {
            if (sum == null || sum.signum() < 0 || sum.compareTo(max) > 0) {
                throw new IllegalStateException("近期统计分数和超出合法范围");
            }
        }
    }
}
