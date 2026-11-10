package com.ginkgo.foodmap.restaurant;

import static org.assertj.core.api.Assertions.*;

import com.ginkgo.foodmap.restaurant.dto.RestaurantListQuery;
import com.ginkgo.foodmap.restaurant.dto.RestaurantSearchDocument;
import com.ginkgo.foodmap.restaurant.policy.RestaurantSearchListPolicy;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

/** 直接使用索引资料时，公开字段、舍入、零值、坐标和错误正文均有明确边界。 */
class RestaurantSearchListPolicyTest {
    static RestaurantSearchDocument document() {
        var d = new RestaurantSearchDocument();
        d.setId(42L);
        d.setProjectionVersion(3L);
        d.setDeleted(false);
        d.setName("索引餐厅");
        d.setRestaurantType("REGULAR");
        d.setCategoryCode("OTHER");
        d.setCategoryName("其他美食");
        d.setTags(List.of("面食"));
        d.setPriceKind("UNKNOWN");
        d.setBusinessStatus("OPERATING");
        d.setNeedsLocation(true);
        d.setParticipantCount(0L);
        d.setReviewCount(0L);
        return d;
    }

    @Test
    void mapsPublicFieldsAndUnknownsWithoutInventingDetails() {
        var d = document();
        d.setCoverPhotoId("801");
        var view = RestaurantSearchListPolicy.toView(d, new RestaurantListQuery());
        assertThat(view.getName()).isEqualTo("索引餐厅");
        assertThat(view.getTags()).containsExactly("面食");
        assertThat(view.getScore()).isNull();
        assertThat(view.getDistanceKm()).isNull();
        assertThat(view.getDimensions().getTaste()).isNull();
        assertThat(view.getCoverPhoto().getUrl()).isEqualTo("/api/restaurants/42/photos/801");
    }

    @Test
    void preservesZeroAndRoundsTheSameWayAsSql() {
        var d = document();
        d.setParticipantCount(1L);
        d.setReviewCount(3L);
        d.setRatingScore(0.0);
        d.setRecommendRate(0.0);
        assertThat(RestaurantSearchListPolicy.toView(d, new RestaurantListQuery()).getScore())
                .isEqualByComparingTo("0");
        d.setRatingScore(8.25);
        d.setRecommendRate(0.9234);
        var view = RestaurantSearchListPolicy.toView(d, new RestaurantListQuery());
        assertThat(view.getScore()).isEqualByComparingTo("8.3");
        assertThat(view.getRecommendRate()).isEqualByComparingTo("0.923");
        assertThat(view.getReviewCount()).isEqualTo(3);
    }

    @Test
    void calculatesDistanceFromRequestLocationAndAllowsCanteensWithoutCategory() {
        var d = document();
        d.setRestaurantType("CANTEEN");
        d.setCategoryCode(null);
        d.setCategoryName(null);
        d.setNeedsLocation(false);
        var location = new RestaurantSearchDocument.Location();
        location.setLat(new BigDecimal("30.75"));
        location.setLon(new BigDecimal("103.93"));
        d.setLocation(location);
        var q = new RestaurantListQuery();
        q.setLatitude(location.getLat());
        q.setLongitude(location.getLon());
        assertThat(RestaurantSearchListPolicy.toView(d, q).getDistanceKm()).isZero();
    }

    @Test
    void rejectsDeletedIncompleteAndNonfiniteDocuments() {
        var d = document();
        d.setDeleted(true);
        assertThatThrownBy(() -> RestaurantSearchListPolicy.toView(d, new RestaurantListQuery()))
                .isInstanceOf(IllegalStateException.class);
        d.setDeleted(false);
        d.setName(null);
        assertThatThrownBy(() -> RestaurantSearchListPolicy.toView(d, new RestaurantListQuery()))
                .isInstanceOf(IllegalStateException.class);
        d.setName("餐厅");
        d.setParticipantCount(1L);
        d.setReviewCount(1L);
        d.setRatingScore(Double.NaN);
        d.setRecommendRate(1.0);
        assertThatThrownBy(() -> RestaurantSearchListPolicy.toView(d, new RestaurantListQuery()))
                .isInstanceOf(IllegalStateException.class);
    }
}
