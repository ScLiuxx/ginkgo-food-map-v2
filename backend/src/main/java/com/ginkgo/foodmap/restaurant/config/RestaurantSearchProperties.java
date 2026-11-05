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
    /** 等待响应的时间上限；异常时查询服务整次回退SQL。 */
    private Duration socketTimeout = Duration.ofSeconds(3);
}
