package com.foodmap.common.persistence;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 的全局技术配置。
 *
 * <p>这里只放所有业务模块共同需要的持久化能力，不放用户查询等业务 SQL。
 * 数据库建表和升级继续由 Flyway 独占，MyBatis-Plus 不参与自动建表。</p>
 */
@Configuration
public class MybatisPlusConfiguration {

    /**
     * 注册 MyBatis-Plus 拦截器链。
     *
     * <p>乐观锁插件配合 Entity 上的 {@code @Version} 字段，在更新时把旧版本加入 WHERE 条件；
     * 并发事务使用陈旧版本更新时返回 0 行，由业务服务转换为并发冲突。</p>
     *
     * @return MyBatis-Plus 拦截器链
     */
    @Bean
    MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        return interceptor;
    }
}
