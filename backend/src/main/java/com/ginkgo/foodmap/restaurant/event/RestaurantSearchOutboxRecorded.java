package com.ginkgo.foodmap.restaurant.event;

/** 事务内已记录同步意图；事件只用于提交后的轻量唤醒，真实任务始终以Outbox为准。 */
public record RestaurantSearchOutboxRecorded() {}
