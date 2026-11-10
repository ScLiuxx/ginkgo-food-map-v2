package com.ginkgo.foodmap.restaurant.policy;

import com.ginkgo.foodmap.restaurant.dto.RestaurantListQuery;
import com.ginkgo.foodmap.restaurant.dto.RestaurantSearchDocument;
import com.ginkgo.foodmap.restaurant.vo.RestaurantPhotoViews;
import com.ginkgo.foodmap.restaurant.vo.RestaurantVo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Set;

/** 将搜索投影映射为公开列表，不访问MySQL；不完整或非法正文使整次查询降级，不伪造零分。 */
public final class RestaurantSearchListPolicy {
    private RestaurantSearchListPolicy() {}

    /** 列表不携带未存入投影的四维与审计时间；详情独立读取权威数据。 */
    public static RestaurantVo toView(RestaurantSearchDocument doc, RestaurantListQuery query) {
        require(
                doc.getId() != null
                        && doc.getId() > 0
                        && doc.getProjectionVersion() != null
                        && doc.getProjectionVersion() > 0
                        && Boolean.FALSE.equals(doc.getDeleted()));
        require(
                doc.getName() != null
                        && !doc.getName().isBlank()
                        && Set.of("REGULAR", "CANTEEN").contains(text(doc.getRestaurantType()))
                        && Set.of("OPERATING", "CLOSED").contains(text(doc.getBusinessStatus()))
                        && Set.of("UNKNOWN", "EXACT", "ESTIMATE").contains(text(doc.getPriceKind()))
                        && doc.getNeedsLocation() != null
                        && doc.getTags() != null
                        && doc.getReviewCount() != null
                        && doc.getParticipantCount() != null
                        && doc.getParticipantCount() >= 0
                        && doc.getReviewCount() >= doc.getParticipantCount());
        if ("REGULAR".equals(doc.getRestaurantType())) {
            require(doc.getCategoryCode() != null && doc.getCategoryName() != null);
        }
        require(
                "UNKNOWN".equals(doc.getPriceKind())
                        ? doc.getPricePerPerson() == null
                        : doc.getPricePerPerson() != null && doc.getPricePerPerson() >= 1);
        var result = new RestaurantVo();
        result.setId(doc.getId().toString());
        result.setName(doc.getName());
        result.setCategoryCode(doc.getCategoryCode());
        result.setCategoryName(doc.getCategoryName());
        result.setRestaurantType(doc.getRestaurantType());
        result.setSupportsDineIn(doc.getSupportsDineIn());
        result.setSupportsDelivery(doc.getSupportsDelivery());
        result.setTags(List.copyOf(doc.getTags()));
        result.setAddress(doc.getAddress());
        result.setPriceKind(doc.getPriceKind());
        result.setPricePerPerson(doc.getPricePerPerson());
        result.setBusinessStatus(doc.getBusinessStatus());
        result.setNeedsLocation(doc.getNeedsLocation());
        result.setReviewCount(doc.getReviewCount());
        result.setParticipantCount(doc.getParticipantCount());
        if (doc.getParticipantCount() == 0) {
            require(doc.getRatingScore() == null && doc.getRecommendRate() == null);
        } else {
            result.setScore(decimal(doc.getRatingScore(), 10, 1));
            result.setRecommendRate(decimal(doc.getRecommendRate(), 1, 3));
        }
        if (!doc.getNeedsLocation()) {
            var location = doc.getLocation();
            require(
                    location != null
                            && location.getLat() != null
                            && location.getLon() != null
                            && location.getLat().abs().compareTo(BigDecimal.valueOf(90)) <= 0
                            && location.getLon().abs().compareTo(BigDecimal.valueOf(180)) <= 0);
            result.setLatitude(location.getLat());
            result.setLongitude(location.getLon());
            if (query.getLatitude() != null) {
                result.setDistanceKm(distance(query, location));
            }
        } else {
            require(doc.getLocation() == null);
        }
        if (doc.getCoverPhotoId() != null) {
            require(doc.getCoverPhotoId().matches("[1-9][0-9]{0,18}"));
            require(
                    new BigDecimal(doc.getCoverPhotoId())
                                    .compareTo(BigDecimal.valueOf(Long.MAX_VALUE))
                            <= 0);
            var cover = new RestaurantPhotoViews.Item();
            cover.setId(doc.getCoverPhotoId());
            cover.setCover(true);
            cover.setPosition(0);
            cover.setUrl("/api/restaurants/" + doc.getId() + "/photos/" + doc.getCoverPhotoId());
            result.setCoverPhoto(cover);
        }
        return result;
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    /** 与SQL公开分数保持相同舍入精度；存储原值仍用于搜索与排序。 */
    private static BigDecimal decimal(Double value, int maximum, int scale) {
        require(value != null && Double.isFinite(value) && value >= 0 && value <= maximum);
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP);
    }

    /** 与现有SQL及ES脚本使用相同半径，以原始坐标计算公里数，不把未知位置当作0距离。 */
    private static double distance(
            RestaurantListQuery query, RestaurantSearchDocument.Location location) {
        double latitude = location.getLat().doubleValue(),
                longitude = location.getLon().doubleValue();
        double origin = query.getLatitude().doubleValue();
        double a =
                Math.pow(Math.sin(Math.toRadians(latitude - origin) / 2), 2)
                        + Math.cos(Math.toRadians(origin))
                                * Math.cos(Math.toRadians(latitude))
                                * Math.pow(
                                        Math.sin(
                                                Math.toRadians(
                                                                longitude
                                                                        - query.getLongitude()
                                                                                .doubleValue())
                                                        / 2),
                                        2);
        return 6371 * 2 * Math.asin(Math.sqrt(Math.min(1, Math.max(0, a))));
    }

    private static void require(boolean valid) {
        if (!valid) throw new IllegalStateException("搜索列表投影不完整或无效");
    }
}
