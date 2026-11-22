package com.foodmap.restaurant.service;

import com.foodmap.restaurant.config.RestaurantRankingProperties;
import com.foodmap.restaurant.entity.RestaurantRankingSnapshotEntity;
import com.foodmap.restaurant.mapper.RestaurantRankingCandidateMapper;
import com.foodmap.restaurant.policy.RestaurantRankingPolicy;
import com.foodmap.restaurant.policy.RestaurantRankingPolicy.Area;
import com.foodmap.restaurant.support.RestaurantRankingKey;
import com.foodmap.review.service.ReviewRankingService;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 只计算并返回同轮四份快照，不写表、不清缓存、不调发布服务，也不获取缓存锁。 独立RR只读事务让候选资料、封面与两次review公开服务查询共享一个已提交数据库视图。
 * 固定T决定统计区间，RR视图决定本轮可见记录；不承诺重建过去某时刻的删除历史。
 */
@Service
@RequiredArgsConstructor
public class RestaurantRankingCalculationService {
    private final RestaurantRankingCandidateMapper candidates;
    private final ReviewRankingService reviews;
    private final RestaurantRankingPolicy policy;
    private final RestaurantRankingProperties properties;
    private final ObjectMapper json;
    private final Clock restaurantRankingClock;

    /**
     * T统一截断为MySQL支持的微秒，采用[T-168/720小时,T)；不能使用未来或已经超龄的截止时刻。
     * 查询/解析/计分任一步失败整次抛出，不返回部分榜单；无评价时返回四份正常空榜计算结果。
     */
    @Transactional(
            readOnly = true,
            isolation = Isolation.REPEATABLE_READ,
            propagation = Propagation.REQUIRES_NEW,
            timeout = 60)
    public List<RestaurantRankingSnapshotEntity> calculate(Instant requestedCutoff) {
        var now = restaurantRankingClock.instant();
        if (requestedCutoff == null || requestedCutoff.isAfter(now)) {
            throw new IllegalArgumentException("榜单截止时间不能为空或在未来");
        }
        var cutoff = requestedCutoff.truncatedTo(ChronoUnit.MICROS);
        if (now.isAfter(cutoff.plus(properties.getMaxSnapshotAge()))) {
            throw new IllegalArgumentException("榜单截止时间已超过展示年龄上限");
        }
        var areas =
                List.of(
                        area("QINGSHUIHE", properties.getQingshuihe()),
                        area("SHAHE", properties.getShahe()));
        var restaurants = candidates.selectCandidates();
        var periods =
                Map.of(
                        "WEEK",
                        reviews.aggregate(cutoff.minus(Duration.ofHours(168)), cutoff),
                        "MONTH",
                        reviews.aggregate(cutoff.minus(Duration.ofHours(720)), cutoff));
        var result = new ArrayList<RestaurantRankingSnapshotEntity>();
        for (var key : RestaurantRankingKey.ALL) {
            var area =
                    areas.stream()
                            .filter(value -> value.campusCode().equals(key.campusCode()))
                            .findFirst()
                            .orElseThrow();
            var items = json.createArrayNode();
            for (var ranked : policy.rank(restaurants, periods.get(key.periodType()), area)) {
                var item = items.addObject();
                item.put("rank", items.size());
                item.put("restaurantId", Long.toString(ranked.restaurant().getId()));
                item.put("name", ranked.restaurant().getName());
                var photo = ranked.restaurant().getCoverPhotoId();
                if (photo == null) {
                    item.putNull("coverUrl");
                } else {
                    item.put(
                            "coverUrl",
                            "/api/restaurants/" + ranked.restaurant().getId() + "/photos/" + photo);
                }
                item.put("tasteScore", ranked.tasteMean());
                item.put("participantCount", ranked.participantCount());
                // 内部核对证据不透传给用户；精确分子/分母保留真正排序依据。
                item.put("sortingScore", ranked.sortingScore());
                item.put("sortingScoreNumerator", ranked.numerator());
                item.put("sortingScoreDenominator", ranked.denominator());
                var sums = item.putObject("scoreSums");
                sums.put("taste", ranked.tasteSum());
                sums.put("value", ranked.valueSum());
                sums.put("service", ranked.serviceSum());
                sums.put("ambience", ranked.ambienceSum());
            }
            var snapshot = new RestaurantRankingSnapshotEntity();
            snapshot.setCampusCode(key.campusCode());
            snapshot.setPeriodType(key.periodType());
            snapshot.setWindowStartAt(cutoff.minus(Duration.ofHours(key.windowHours())));
            snapshot.setWindowEndAt(cutoff);
            snapshot.setRulesSnapshot(json.writeValueAsString(rules(area)));
            snapshot.setItems(json.writeValueAsString(items));
            result.add(snapshot);
        }
        var generatedAt = restaurantRankingClock.instant().truncatedTo(ChronoUnit.MICROS);
        if (generatedAt.isBefore(cutoff)) {
            throw new IllegalStateException("计算期间时钟回拨，不能生成未来快照");
        }
        result.forEach(snapshot -> snapshot.setGeneratedAt(generatedAt));
        return List.copyOf(result);
    }

    private static Area area(String code, RestaurantRankingProperties.Campus campus) {
        if (campus == null) {
            throw new IllegalArgumentException("榜单校区配置不能为空");
        }
        return new Area(
                code, campus.getLatitude(), campus.getLongitude(), campus.getRadiusMeters());
    }

    /** 保存实际算法、分制、边界及校区参数，后续读旧快照无需依赖当前配置重新计算排名。 */
    private ObjectNode rules(Area area) {
        var rules = json.createObjectNode();
        rules.put("formatVersion", 1);
        rules.put("algorithmVersion", "RECENT_REPUTATION_V2");
        rules.put("scoreScale", 5);
        rules.put("sourceScoreScale", 10);
        rules.put("c", RestaurantRankingPolicy.C);
        rules.put("m", RestaurantRankingPolicy.M);
        rules.put("minParticipants", RestaurantRankingPolicy.MIN_PARTICIPANTS);
        rules.put("minRawScoreExclusive", RestaurantRankingPolicy.MIN_RAW_SCORE);
        rules.put("limit", RestaurantRankingPolicy.LIMIT);
        rules.put("interval", "[start,end)");
        rules.put("latestReviewOrder", "created_at DESC,id DESC");
        rules.put("sortOrder", "W DESC,participantCount DESC,restaurantId ASC");
        rules.put("sortingComparison", "exact numerator/denominator cross multiplication");
        rules.put("storedMeanScale", 16);
        rules.put("roundingMode", "HALF_UP");
        var weights = rules.putObject("weights");
        weights.put("taste", RestaurantRankingPolicy.TASTE_WEIGHT);
        weights.put("value", RestaurantRankingPolicy.VALUE_WEIGHT);
        weights.put("service", RestaurantRankingPolicy.SERVICE_WEIGHT);
        weights.put("ambience", RestaurantRankingPolicy.AMBIENCE_WEIGHT);
        var campus = rules.putObject("campus");
        campus.put("code", area.campusCode());
        campus.put("coordinateSystem", "GCJ02");
        campus.put("latitude", area.latitude());
        campus.put("longitude", area.longitude());
        campus.put("radiusMeters", area.radiusMeters());
        campus.put("earthRadiusMeters", 6371000);
        return rules;
    }
}
