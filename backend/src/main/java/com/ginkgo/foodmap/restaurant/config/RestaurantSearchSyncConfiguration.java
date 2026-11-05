package com.ginkgo.foodmap.restaurant.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 消费开关独立于连接开关；开启消费也必须开启ES连接，否则依赖缺失使启动失败而非静默失效。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.search.sync.enabled", havingValue = "true")
@EnableConfigurationProperties(RestaurantSearchSyncProperties.class)
@EnableScheduling
public class RestaurantSearchSyncConfiguration { }
