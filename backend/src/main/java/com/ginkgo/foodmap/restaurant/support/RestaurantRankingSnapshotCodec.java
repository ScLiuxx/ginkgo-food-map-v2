package com.ginkgo.foodmap.restaurant.support;

import com.ginkgo.foodmap.restaurant.entity.RestaurantRankingSnapshotEntity;
import com.ginkgo.foodmap.restaurant.vo.RestaurantRankingVo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.time.Duration;
import java.time.Instant;

/** 解析格式v1并显式复制公开字段；结构非法时拒绝整榜，不展示半份结果或泄露内部JSON。 */
@Component
@RequiredArgsConstructor
public class RestaurantRankingSnapshotCodec {
    private final ObjectMapper json;

    /** MySQL与Redis使用相同投影和年龄规则，每次命中也检查截止时间。 */
    public RestaurantRankingVo view(RestaurantRankingSnapshotEntity row, RestaurantRankingKey key,
            Instant now, Duration maxAge) {
        var result = new RestaurantRankingVo();
        result.setCampusCode(key.campusCode());
        result.setPeriodType(key.periodType());
        if (row == null) {
            result.setStatus("PREPARING");
            return result;
        }
        require(key.campusCode().equals(row.getCampusCode()) && key.periodType().equals(row.getPeriodType()));
        require(row.getWindowStartAt() != null && row.getWindowEndAt() != null && row.getGeneratedAt() != null);
        require(row.getWindowStartAt().plus(Duration.ofHours(key.windowHours())).equals(row.getWindowEndAt()));
        require(!row.getGeneratedAt().isBefore(row.getWindowEndAt()) && !row.getGeneratedAt().isAfter(now));
        result.setWindowStartAt(row.getWindowStartAt());
        result.setWindowEndAt(row.getWindowEndAt());
        result.setGeneratedAt(row.getGeneratedAt());
        if (now.isAfter(row.getWindowEndAt().plus(maxAge))) {
            result.setStatus("UNAVAILABLE");
            return result;
        }
        result.setItems(decodeItems(row));
        result.setStatus("READY");
        return result;
    }

    /** 保存完整元数据、规则对象和原始榜单数组，不把舍入后的HTTP对象当作计算快照。 */
    public String encodeCache(RestaurantRankingSnapshotEntity row, RestaurantRankingKey key, Instant now) {
        var root = json.createObjectNode();
        root.put("formatVersion", 1);
        root.put("campusCode", key.campusCode());
        root.put("periodType", key.periodType());
        if (row == null) {
            root.put("status", "PREPARING");
            root.put("cachedAt", now.toString());
        } else {
            root.put("status", "READY");
            root.put("windowStartAt", row.getWindowStartAt().toString());
            root.put("windowEndAt", row.getWindowEndAt().toString());
            root.put("generatedAt", row.getGeneratedAt().toString());
            root.set("rulesSnapshot", json.readTree(row.getRulesSnapshot()));
            root.set("items", json.readTree(row.getItems()));
        }
        return json.writeValueAsString(root);
    }

    /** 损坏、跨榜、未来时间或超龄的准备中标记均由调用方回源修复。 */
    public CachedSnapshot decodeCache(String value, RestaurantRankingKey key,
            Instant now, Duration preparingTtl) {
        var root = json.readTree(value);
        require(root != null && root.isObject() && integer(root.get("formatVersion")) == 1);
        require(key.campusCode().equals(text(root.get("campusCode")))
                && key.periodType().equals(text(root.get("periodType"))));
        String status = text(root.get("status"));
        if ("PREPARING".equals(status)) {
            var cachedAt = Instant.parse(text(root.get("cachedAt")));
            require(!cachedAt.isAfter(now) && now.isBefore(cachedAt.plus(preparingTtl)));
            return new CachedSnapshot(null, cachedAt.plus(preparingTtl));
        }
        require("READY".equals(status));
        var row = new RestaurantRankingSnapshotEntity();
        row.setCampusCode(key.campusCode());
        row.setPeriodType(key.periodType());
        row.setWindowStartAt(Instant.parse(text(root.get("windowStartAt"))));
        row.setWindowEndAt(Instant.parse(text(root.get("windowEndAt"))));
        row.setGeneratedAt(Instant.parse(text(root.get("generatedAt"))));
        require(root.hasNonNull("rulesSnapshot") && root.hasNonNull("items"));
        row.setRulesSnapshot(root.get("rulesSnapshot").toString());
        row.setItems(root.get("items").toString());
        return new CachedSnapshot(row, null);
    }

    /** 额外携带准备中原过期时间，跨层回填不能把其短TTL重新起算。 */
    public record CachedSnapshot(RestaurantRankingSnapshotEntity row, Instant preparingExpiresAt) {
    }

    /** 对已有快照的内部格式做读取保护；这不代替后续生成服务对计分和排序的完整校验。 */
    public List<RestaurantRankingVo.Item> decodeItems(RestaurantRankingSnapshotEntity row) {
        var rules = json.readTree(row.getRulesSnapshot());
        require(rules != null && rules.isObject());
        require(integer(rules.get("formatVersion")) == 1);
        require(integer(rules.get("scoreScale")) == 5);
        var items = json.readTree(row.getItems());
        require(items != null && items.isArray() && items.size() <= 20);
        var result = new ArrayList<RestaurantRankingVo.Item>();
        var ids = new HashSet<String>();
        for (var node : items) {
            require(node.isObject());
            var item = new RestaurantRankingVo.Item();
            long rank = integer(node.get("rank"));
            require(rank == result.size() + 1);
            item.setRank((int) rank);
            String id = text(node.get("restaurantId"));
            require(id.matches("[1-9][0-9]{0,18}"));
            require(new BigDecimal(id).compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) <= 0);
            require(ids.add(id));
            item.setRestaurantId(id);
            String name = text(node.get("name"));
            require(!name.isBlank() && name.codePointCount(0, name.length()) <= 40);
            item.setName(name);
            var score = node.get("tasteScore");
            require(score != null && score.isNumber());
            BigDecimal taste = score.decimalValue();
            require(taste.compareTo(BigDecimal.valueOf(4)) >= 0
                    && taste.compareTo(BigDecimal.valueOf(5)) <= 0);
            item.setTasteScore(taste.setScale(1, RoundingMode.HALF_UP));
            // 旧计算快照已经保留sortingScore；公开推荐分只投影该值，不重算历史规则。
            var recommendation = node.get("sortingScore");
            if (recommendation != null && recommendation.isNumber()) {
                var value = recommendation.decimalValue();
                require(value.signum() >= 0 && value.compareTo(BigDecimal.valueOf(5)) <= 0);
                item.setRecommendationScore(value.setScale(1, RoundingMode.HALF_UP));
            }
            long participants = integer(node.get("participantCount"));
            require(participants >= 3);
            item.setParticipantCount(participants);
            var cover = node.get("coverUrl");
            if (cover != null && !cover.isNull()) {
                String url = text(cover);
                // 不允许外链、签名地址或跨店封面；图片访问仍由既有接口校验关联及可见性。
                require(url.startsWith("/api/restaurants/" + id + "/photos/"));
                String photoId = url.substring(("/api/restaurants/" + id + "/photos/").length());
                require(photoId.matches("[1-9][0-9]{0,18}"));
                require(new BigDecimal(photoId).compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) <= 0);
                item.setCoverUrl(url);
            }
            result.add(item);
        }
        return List.copyOf(result);
    }

    private static long integer(JsonNode value) {
        require(value != null && value.isIntegralNumber() && value.canConvertToLong());
        return value.longValue();
    }

    private static String text(JsonNode value) {
        require(value != null && value.isTextual());
        return value.stringValue();
    }

    private static void require(boolean condition) {
        if (!condition) {
            throw new IllegalArgumentException("Invalid restaurant ranking snapshot");
        }
    }
}
