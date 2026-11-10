package com.ginkgo.foodmap.restaurant.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 开启单实例提交后唤醒及定时兜底；也必须开启ES连接，依赖缺失时启动失败而非静默失效。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.search.sync.enabled", havingValue = "true")
@EnableConfigurationProperties(RestaurantSearchSyncProperties.class)
@EnableScheduling
public class RestaurantSearchSyncConfiguration {}
