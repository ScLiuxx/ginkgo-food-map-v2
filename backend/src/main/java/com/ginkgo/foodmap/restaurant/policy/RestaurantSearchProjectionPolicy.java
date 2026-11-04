package com.ginkgo.foodmap.restaurant.policy;

import com.ginkgo.foodmap.restaurant.dto.RestaurantSearchDocument;
import com.ginkgo.foodmap.restaurant.entity.RestaurantSearchSource;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.List;
import java.util.Set;

/** 搜索投影纯规则：校验业务组合并组装白名单，不查询数据库、不生成版本、不发送ES请求。 */
public final class RestaurantSearchProjectionPolicy {
    private RestaurantSearchProjectionPolicy() { }

    /**
     * version必须由内部调用方显式提供；这里只检查正数，不声称具备持久版本或乱序保护。
     * 已删除商户仅生成最小墓碑；不携带旧名称、标签、位置或封面使其意外公开。
     */
    public static RestaurantSearchDocument assemble(RestaurantSearchSource row, List<String> tags,
                                                     String coverPhotoId, long version) {
        require(row != null && row.getId() != null && row.getId() > 0 && version > 0);
        var document = new RestaurantSearchDocument();
        document.setId(row.getId());
        document.setProjectionVersion(version);
        document.setDeleted(row.isDeleted());
        if (row.isDeleted()) return document;

        text(row.getName(), 40, false);
        text(row.getAddress(), 80, true);
        require(Set.of("REGULAR", "CANTEEN").contains(nonNull(row.getRestaurantType())));
        if ("CANTEEN".equals(row.getRestaurantType())) {
            require(row.getCategoryCode() == null && row.getCategoryName() == null);
        } else {
            require(row.getCategoryCode() != null && row.getCategoryCode().matches("[A-Z][A-Z0-9_]{0,31}"));
            // 左连接字典后没有分类名表示来源不完整；禁止默默改成OTHER或空字符串。
            text(row.getCategoryName(), 20, false);
        }
        require(Set.of("UNKNOWN", "EXACT", "ESTIMATE").contains(nonNull(row.getPriceKind())));
        if ("UNKNOWN".equals(row.getPriceKind())) require(row.getPricePerPerson() == null);
        else require(row.getPricePerPerson() != null && row.getPricePerPerson() >= 1 && row.getPricePerPerson() <= 5000);
        require(Set.of("OPERATING", "CLOSED").contains(nonNull(row.getBusinessStatus())));
        require(tags != null && tags.size() <= 8);
        int length = 0;
        for (String tag : tags) {
            text(tag, 80, false);
            length += tag.codePointCount(0, tag.length());
        }
        require(length <= 80);
        require(tags.stream().distinct().count() == tags.size());
        if (coverPhotoId != null) {
            require(coverPhotoId.matches("[1-9][0-9]{0,18}"));
            try { require(Long.parseLong(coverPhotoId) > 0); }
            catch (NumberFormatException error) { throw invalid(); }
        }

        document.setName(row.getName());
        document.setCategoryCode(row.getCategoryCode());
        document.setCategoryName(row.getCategoryName());
        document.setRestaurantType(row.getRestaurantType());
        document.setSupportsDineIn(row.getSupportsDineIn());
        document.setSupportsDelivery(row.getSupportsDelivery());
        document.setPricePerPerson(row.getPricePerPerson());
        document.setPriceKind(row.getPriceKind());
        document.setAddress(row.getAddress());
        document.setBusinessStatus(row.getBusinessStatus());
        document.setTags(List.copyOf(tags));
        document.setCoverPhotoId(coverPhotoId);
        document.setNeedsLocation(row.isNeedsLocation());
        if (row.isNeedsLocation()) {
            require(row.getLatitude() == null && row.getLongitude() == null);
        } else {
            require(inRange(row.getLatitude(), "3", "54") && inRange(row.getLongitude(), "73", "136"));
            var location = new RestaurantSearchDocument.Location();
            location.setLat(row.getLatitude());
            location.setLon(row.getLongitude());
            document.setLocation(location);
        }

        long participants = row.getParticipantCount();
        require(participants >= 0 && row.getReviewCount() >= participants
                && row.getRecommendCount() >= 0 && row.getRecommendCount() <= participants);
        require(row.getOverallScoreSum() != null && row.getOverallScoreSum().signum() >= 0
                && row.getOverallScoreSum().compareTo(BigDecimal.valueOf(participants).multiply(BigDecimal.TEN)) <= 0);
        document.setParticipantCount(participants);
        document.setReviewCount(row.getReviewCount());
        if (participants > 0) {
            // 先使用累计值/人数，最后转ES double；不先舍入为页面两位分数，也不对无人计分填0。
            var divisor = BigDecimal.valueOf(participants);
            document.setRatingScore(row.getOverallScoreSum().divide(divisor, MathContext.DECIMAL128).doubleValue());
            document.setRecommendRate(BigDecimal.valueOf(row.getRecommendCount()).divide(divisor, MathContext.DECIMAL128).doubleValue());
        }
        return document;
    }

    private static boolean inRange(BigDecimal value, String minimum, String maximum) {
        return value != null && value.compareTo(new BigDecimal(minimum)) >= 0 && value.compareTo(new BigDecimal(maximum)) <= 0;
    }

    private static void text(String value, int maximum, boolean nullable) {
        if (value == null && nullable) return;
        require(value != null && !value.isBlank() && value.equals(value.strip())
                && value.codePointCount(0, value.length()) <= maximum);
    }

    private static String nonNull(String value) { return value == null ? "" : value; }
    private static void require(boolean condition) { if (!condition) throw invalid(); }
    private static IllegalArgumentException invalid() {
        // 不把源字段、审核内容或存储地址拼入异常日志。
        return new IllegalArgumentException("商户搜索投影来源或版本不符合业务契约");
    }
}
