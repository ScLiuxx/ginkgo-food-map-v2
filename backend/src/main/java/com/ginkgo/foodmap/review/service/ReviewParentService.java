package com.ginkgo.foodmap.review.service;

import com.ginkgo.foodmap.restaurant.service.CanteenWindowService;
import com.ginkgo.foodmap.restaurant.service.RestaurantReadService;
import com.ginkgo.foodmap.restaurant.service.RestaurantReviewApplicationService;
import com.ginkgo.foodmap.review.entity.ReviewEntity;
import com.ginkgo.foodmap.review.mapper.ReviewDeleteMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** review自有父关系的统一出口；明确区分restaurant_id和window_id，不编码负数或伪造餐厅。 */
@Service
@RequiredArgsConstructor
public class ReviewParentService {
    private final ReviewDeleteMapper reviews;
    private final RestaurantReadService restaurants;
    private final RestaurantReviewApplicationService locks;
    private final CanteenWindowService windows;

    /** 普通快照读取，停业可读，任一父层删除后不公开。 */
    public ReviewEntity visible(long review) {
        var row = reviews.find(review);
        if (row == null) {
            return null;
        }
        if (row.getWindowId() != null) {
            windows.requireVisible(row.getWindowId());
        } else {
            restaurants.requireVisible(row.getRestaurantId());
        }
        return row;
    }

    /** 所有点评子资源均先锁父食堂→窗口→点评，删除和互动共用相同顺序。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public ReviewEntity lock(long review) {
        var row = reviews.find(review);
        if (row == null) {
            return null;
        }
        if (row.getWindowId() != null) {
            windows.lockVisible(row.getWindowId(), false);
        } else {
            locks.lockVisibleParent(row.getRestaurantId());
        }
        return reviews.lock(review);
    }
}
