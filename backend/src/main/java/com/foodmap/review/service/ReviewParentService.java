package com.foodmap.review.service;

import com.foodmap.restaurant.service.RestaurantReadService;
import com.foodmap.restaurant.service.RestaurantReviewApplicationService;
import com.foodmap.review.entity.ReviewEntity;
import com.foodmap.review.mapper.ReviewDeleteMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** review自有父关系的统一出口；餐厅与食堂统一使用restaurant_id。 */
@Service
@RequiredArgsConstructor
public class ReviewParentService {
    private final ReviewDeleteMapper reviews;
    private final RestaurantReadService restaurants;
    private final RestaurantReviewApplicationService locks;

    /** 普通快照读取，停业可读，任一父层删除后不公开。 */
    public ReviewEntity visible(long review) {
        var row = reviews.find(review);
        if (row == null) {
            return null;
        }
        restaurants.requireVisible(row.getRestaurantId());
        return row;
    }

    /** 所有点评子资源均先锁父店→点评，删除和互动共用相同顺序。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public ReviewEntity lock(long review) {
        var row = reviews.find(review);
        if (row == null) {
            return null;
        }
        locks.lockVisibleParent(row.getRestaurantId());
        return reviews.lock(review);
    }
}
