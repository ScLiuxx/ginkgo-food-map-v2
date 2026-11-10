package com.ginkgo.foodmap.restaurant.service;

import com.ginkgo.foodmap.restaurant.event.RestaurantSearchOutboxRecorded;
import com.ginkgo.foodmap.restaurant.mapper.RestaurantSearchOutboxMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 事务内记录搜索重建意图；不读取完整投影、不连接ES、不自行提交或吞掉异常。 */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class RestaurantSearchOutboxService {
    private final RestaurantSearchOutboxMapper mapper;
    private final ApplicationEventPublisher events;

    /** 仅在主行创建/版本推进后且仍持有写锁时调用；任务失败必须使外层业务整体回滚。 */
    public void record(long restaurantId) {
        if (restaurantId <= 0) {
            throw new IllegalArgumentException("商户ID必须为正数");
        }
        if (mapper.insertCurrentVersion(restaurantId) != 1) {
            throw new IllegalStateException("搜索同步任务对应的商户不存在");
        }
        // 事务监听器只在提交后唤醒，不在事务内查投影或写ES；回滚不会产生消费通知。
        events.publishEvent(new RestaurantSearchOutboxRecorded());
    }
}
