package com.ginkgo.foodmap.restaurant.config;

import lombok.Data;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** 餐厅搜索连接参数；查询入口由独立query开关控制，连接也供Outbox同步复用。 */
@Data
@ConfigurationProperties(prefix = "app.search.elasticsearch")
public class RestaurantSearchProperties {
    /** 默认关闭，使未部署ES的环境无需连接搜索服务。 */
    private boolean enabled = false;

    /** 当前仅用于本地无认证HTTP连接，格式为host:port，不包含协议或路径。 */
    private String address = "127.0.0.1:9200";

    /** 建立连接的等待上限；不能无限等待未启动的服务。 */
    private Duration connectTimeout = Duration.ofSeconds(2);

    /** 单次ES HTTP调用等待响应的上限，同时配置socket与请求级超时；查询异常时整次回退SQL。 */
    private Duration socketTimeout = Duration.ofSeconds(3);
}
