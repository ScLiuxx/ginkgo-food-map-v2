package com.ginkgo.foodmap.security.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/** 机器采集使用独立Basic认证链，既不依赖业务数据库/Redis，也不授予任何业务接口权限。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.security.metrics.enabled", havingValue = "true")
@EnableConfigurationProperties(MetricsScrapeProperties.class)
public class MetricsScrapeSecurityConfiguration {
    /** 仅匹配精确指标路径且携带Basic头；Bearer管理员仍由既有JWT安全链处理。 */
    @Bean
    @Order(1)
    SecurityFilterChain metricsScrapeSecurityFilterChain(
            HttpSecurity http, MetricsScrapeProperties properties, PasswordEncoder passwords)
            throws Exception {
        String password = properties.getPassword();
        // 不使用会回显rejected value的绑定校验；无凭据或过短时拒绝启动，绝不自动生成默认密码。
        if (password == null
                || password.length() < 32
                || password.length() > 64
                || !password.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalStateException("采集密码必须配置为32至64位随机字母数字或下划线/短横线");
        }
        var users =
                new InMemoryUserDetailsManager(
                        User.withUsername("prometheus")
                                .password(passwords.encode(password))
                                .roles("METRICS_SCRAPER")
                                .build());
        // Provider仅加入本链，不注册为全局账号服务，采集身份不能被其他接口复用。
        var provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(passwords);
        var endpoint = PathPatternRequestMatcher.withDefaults().matcher("/actuator/prometheus");
        return http.securityMatcher(
                        request -> {
                            String authorization = request.getHeader("Authorization");
                            return endpoint.matches(request)
                                    && authorization != null
                                    && authorization.regionMatches(true, 0, "Basic ", 0, 6);
                        })
                .csrf(csrf -> csrf.disable())
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .authenticationProvider(provider)
                .authorizeHttpRequests(
                        authorize ->
                                authorize
                                        .requestMatchers(HttpMethod.GET, "/actuator/prometheus")
                                        .hasRole("METRICS_SCRAPER")
                                        .anyRequest()
                                        .denyAll())
                .httpBasic(basic -> basic.realmName("metrics"))
                .build();
    }
}
