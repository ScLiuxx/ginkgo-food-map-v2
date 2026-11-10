package com.ginkgo.foodmap.security.config;

import com.ginkgo.foodmap.common.api.ApiResponse;
import com.ginkgo.foodmap.security.authentication.SessionJwtAuthenticationConverter;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * 全局 Web 安全入口，集中声明公开接口和受保护接口的边界。
 *
 * <p>使用 Bearer JWT 并继续校验 Redis 会话。公开接口必须显式列出，其他请求默认需要认证。
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {

    /**
     * 构建服务端统一安全过滤链。
     *
     * <p>只从 Authorization 请求头读取凭证，不创建 HTTP Session 或使用 Cookie 认证，因此关闭 CSRF。 禁止 HTTP
     * Basic、表单登录和默认退出过滤器，避免绕过业务会话。
     *
     * @param http Spring Security HTTP 配置入口
     * @return 应用于所有请求的安全过滤链
     * @throws Exception 安全过滤链构建失败时抛出
     */
    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http, SessionJwtAuthenticationConverter converter, ObjectMapper json)
            throws Exception {
        var entryPoint =
                (org.springframework.security.web.AuthenticationEntryPoint)
                        (request, response, exception) -> {
                            if (exception
                                            instanceof
                                            org.springframework.security.oauth2.core
                                                                    .OAuth2AuthenticationException
                                                            oauth
                                    && "temporarily_unavailable"
                                            .equals(oauth.getError().getErrorCode())) {
                                writeError(
                                        response,
                                        json,
                                        503,
                                        "AUTH_SERVICE_UNAVAILABLE",
                                        "登录服务暂时不可用，请稍后重试");
                            } else {
                                response.setHeader("WWW-Authenticate", "Bearer");
                                writeError(
                                        response,
                                        json,
                                        401,
                                        "AUTH_UNAUTHENTICATED",
                                        "登录状态无效或已过期，请重新登录");
                            }
                        };
        var deniedHandler =
                (org.springframework.security.web.access.AccessDeniedHandler)
                        (request, response, exception) ->
                                writeError(response, json, 403, "AUTH_FORBIDDEN", "没有权限执行此操作");
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .authorizeHttpRequests(
                        authorize ->
                                authorize
                                        // 指标包含内部运行信息；仅GET允许管理员读取，沿用JWT及Redis会话校验。
                                        .requestMatchers(HttpMethod.GET, "/actuator/prometheus")
                                        .hasRole("ADMIN")
                                        // 禁止其他方法绕过GET规则进入默认的“已登录即可访问”分支。
                                        .requestMatchers(
                                                "/actuator/prometheus", "/actuator/prometheus/**")
                                        .denyAll()
                                        // 管理命名空间统一要求数据库当前管理员角色，避免仅凭前端入口隐藏授权。
                                        .requestMatchers("/api/admin/**")
                                        .hasRole("ADMIN")
                                        // 直接维护正式资料统一收紧到管理员；投稿、纠错等嵌套接口仍按原认证规则执行。
                                        .requestMatchers(
                                                HttpMethod.POST,
                                                "/api/restaurants",
                                                "/api/restaurants/{id}/restore")
                                        .hasRole("ADMIN")
                                        .requestMatchers(HttpMethod.PUT, "/api/restaurants/{id}")
                                        .hasRole("ADMIN")
                                        .requestMatchers(HttpMethod.DELETE, "/api/restaurants/{id}")
                                        .hasRole("ADMIN")
                                        .requestMatchers(
                                                HttpMethod.GET, "/api/restaurants/{id}/management")
                                        .hasRole("ADMIN")
                                        .requestMatchers(
                                                HttpMethod.GET,
                                                "/api/canteen-windows/{id}",
                                                "/api/canteen-windows/{id}/photos",
                                                "/api/canteen-windows/{id}/photos/{fileId}",
                                                "/api/canteen-windows/{id}/reviews",
                                                "/api/canteen-windows/{id}/reviews/authors/{author}/history",
                                                "/api/restaurants/{id}/windows")
                                        .permitAll()
                                        .requestMatchers(
                                                HttpMethod.GET,
                                                "/api/content-likes",
                                                "/actuator/health",
                                                "/api/public/**")
                                        .permitAll()
                                        .requestMatchers(
                                                HttpMethod.GET,
                                                "/api/reviews/{reviewId}/photos/{position}")
                                        .permitAll()
                                        // 正式商户相册仅开放明确GET路径，服务逐次验证公开关联及父商户；申请图片仍仅管理员可读。
                                        .requestMatchers(
                                                HttpMethod.GET,
                                                "/api/restaurants/{id}/photos",
                                                "/api/restaurants/{id}/photos/{photoId}")
                                        .permitAll()
                                        // 回复仅公开读取；发布仍要求JWT及有效账号会话。
                                        .requestMatchers(
                                                HttpMethod.GET, "/api/reviews/{reviewId}/replies")
                                        .permitAll()
                                        // 仅开放主卡列表；历史、本人上下文及管理接口仍保留独立权限边界。
                                        .requestMatchers(
                                                HttpMethod.GET,
                                                "/api/restaurants/{restaurantId}/reviews")
                                        .permitAll()
                                        // 历史为公开内容，仍逐次验证餐厅可见性；本人上下文继续要求认证。
                                        .requestMatchers(
                                                HttpMethod.GET,
                                                "/api/restaurants/{restaurantId}/reviews/authors/{authorId}/history")
                                        .permitAll()
                                        // 只允许餐厅阅读的三个GET入口；不放开后续写入、管理或嵌套资源。
                                        .requestMatchers(
                                                HttpMethod.GET,
                                                "/api/restaurant-categories",
                                                "/api/restaurants",
                                                "/api/restaurants/{id}",
                                                "/api/restaurants/map-points")
                                        .permitAll()
                                        // 只公开明确列出的认证 POST 接口，不放开整个 /auth 路径。
                                        .requestMatchers(
                                                HttpMethod.POST,
                                                "/auth/register",
                                                "/auth/register-and-login",
                                                "/auth/login",
                                                "/auth/password-reset",
                                                "/auth/email/code/register",
                                                "/auth/email/code/password-reset")
                                        .permitAll()
                                        .anyRequest()
                                        .authenticated())
                .exceptionHandling(
                        errors ->
                                errors.authenticationEntryPoint(entryPoint)
                                        .accessDeniedHandler(deniedHandler))
                .oauth2ResourceServer(
                        resource ->
                                resource.authenticationEntryPoint(entryPoint)
                                        .accessDeniedHandler(deniedHandler)
                                        .jwt(jwt -> jwt.jwtAuthenticationConverter(converter)))
                .build();
    }

    /** 过滤器异常发生在 Controller 之外，仍返回统一且不含内部信息的 JSON。 */
    private void writeError(
            HttpServletResponse response,
            ObjectMapper json,
            int status,
            String code,
            String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write(json.writeValueAsString(ApiResponse.error(code, message)));
    }

    /**
     * 提供统一的密码哈希组件。
     *
     * <p>BCrypt 会为每个密码自动生成随机盐，即使两个用户使用相同密码，数据库中的哈希也不同。 使用框架默认强度可兼顾当前 2
     * 核服务器的安全性和响应时间，业务代码不得自行实现密码算法。
     *
     * @return Spring Security BCrypt 密码编码器
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
