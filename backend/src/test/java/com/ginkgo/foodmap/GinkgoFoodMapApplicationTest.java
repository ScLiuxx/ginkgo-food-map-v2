package com.ginkgo.foodmap;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** 应用上下文烟雾测试，确保模块配置、类型安全配置绑定和基础设施 Bean 可以共同启动。 */
@ActiveProfiles("test")
@SpringBootTest
@org.springframework.context.annotation.Import(com.ginkgo.foodmap.security.TestJwtKeys.class)
class GinkgoFoodMapApplicationTest {

    /** 上下文成功启动即为测试通过；配置缺失或 Bean 依赖错误会在测试方法执行前使测试失败。 */
    @Test
    void applicationContextStarts() {}
}
