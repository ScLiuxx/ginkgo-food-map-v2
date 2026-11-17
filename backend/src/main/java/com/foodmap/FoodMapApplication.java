package com.foodmap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 泥电美食地图后端应用入口。
 *
 * <p>{@link ConfigurationPropertiesScan} 从根包开始扫描各业务模块拥有的类型安全配置，
 * 使用户规则留在 {@code user} 模块、认证规则留在 {@code security} 模块，避免形成全局配置杂物箱。</p>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class FoodMapApplication {

    /**
     * 启动 Spring Boot 模块化单体应用。
     *
     * @param args JVM 启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(FoodMapApplication.class, args);
    }
}
