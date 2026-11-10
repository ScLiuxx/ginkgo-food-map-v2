package com.ginkgo.foodmap.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ginkgo.foodmap.restaurant.dto.RestaurantListQuery;
import com.ginkgo.foodmap.restaurant.metrics.RestaurantSearchMetrics;
import com.ginkgo.foodmap.restaurant.vo.RestaurantPageVo;
import com.ginkgo.foodmap.restaurant.vo.RestaurantSearchStatus;
import com.ginkgo.foodmap.security.config.AuthenticationProperties;
import com.ginkgo.foodmap.security.service.LoginSessionService;
import com.ginkgo.foodmap.user.dto.UserIdentity;
import com.ginkgo.foodmap.user.service.UserAccountService;

import io.micrometer.core.instrument.MeterRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.endpoint.web.WebEndpointsSupplier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 使用真实Actuator映射、JWT签名解码及安全规则；仅替换账号和会话查询，不访问真实数据库或Redis。 */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc(print = org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE)
@Import(TestJwtKeys.class)
class PrometheusEndpointTest {
    @Autowired private MockMvc mvc;
    @Autowired private WebEndpointsSupplier endpoints;
    @Autowired private JwtEncoder encoder;
    @Autowired private AuthenticationProperties properties;
    @Autowired private MeterRegistry meters;
    @MockitoBean private UserAccountService accounts;
    @MockitoBean private LoginSessionService sessions;

    /** 匿名访问不能读取内部运行指标。 */
    @Test
    void anonymousCannotScrape() throws Exception {
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
    }

    /** 普通登录用户也不具备监控读取权限。 */
    @Test
    void userCannotScrape() throws Exception {
        mvc.perform(get("/actuator/prometheus").header("Authorization", token("ROLE_USER")))
                .andExpect(status().isForbidden());
    }

    /** 管理员可获取真实Prometheus文本及JVM指标，而非业务JSON包装。 */
    @Test
    void administratorCanScrapeJvmMetrics() throws Exception {
        var response =
                mvc.perform(
                                get("/actuator/prometheus")
                                        .header("Authorization", token("ROLE_ADMIN")))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse();
        assertThat(response.getContentType()).startsWith("text/plain");
        assertThat(response.getContentAsString())
                .contains("# HELP", "# TYPE", "jvm_memory_used_bytes");
    }

    /** 采集端点只允许GET，不因登录而开放其他请求方法。 */
    @Test
    void scrapeIsReadOnly() throws Exception {
        mvc.perform(post("/actuator/prometheus").header("Authorization", token("ROLE_ADMIN")))
                .andExpect(status().isForbidden());
    }

    /** 验证真实Prometheus导出名称、计数和直方图，防止只在SimpleRegistry中可见。 */
    @Test
    void exportsSearchCountersAndHistogramBuckets() throws Exception {
        var query = new RestaurantListQuery();
        var search = new RestaurantSearchStatus();
        search.setEngine("ES");
        var page = new RestaurantPageVo();
        page.setTotal(0);
        RestaurantSearchMetrics.list(meters, query, search, page, System.nanoTime());
        RestaurantSearchMetrics.es(meters, query, System.nanoTime(), 1, null);
        RestaurantSearchMetrics.request(meters, query, "search");
        RestaurantSearchMetrics.fallback(meters, "ES_UNAVAILABLE", false);
        var response =
                mvc.perform(
                                get("/actuator/prometheus")
                                        .header("Authorization", token("ROLE_ADMIN")))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(response)
                .contains(
                        "restaurant_search_requests_total{",
                        "restaurant_search_results_total{",
                        "restaurant_search_duration_seconds_bucket{",
                        "restaurant_search_es_duration_seconds_bucket{",
                        "restaurant_search_es_requests_total{",
                        "restaurant_search_es_requests_per_query_sum{",
                        "restaurant_search_es_requests_per_query_count{",
                        "restaurant_search_fallbacks_total{");
    }

    /** 从实际Web端点注册表确认未暴露env、metrics等其他管理能力。 */
    @Test
    void otherManagementEndpointsRemainUnexposed() {
        assertThat(endpoints.getEndpoints())
                .extracting(endpoint -> endpoint.getEndpointId().toString())
                .containsExactlyInAnyOrder("health", "info", "prometheus");
    }

    /** 注销后的管理员令牌也不得采集指标，不能绕过会话验证。 */
    @Test
    void revokedSessionCannotScrape() throws Exception {
        String authorization = token("ROLE_ADMIN");
        when(sessions.isActive("00000000-0000-0000-0000-000000000001", 1L, 0L)).thenReturn(false);
        mvc.perform(get("/actuator/prometheus").header("Authorization", authorization))
                .andExpect(status().isUnauthorized());
    }

    /** 签发短期测试JWT；角色从模拟账号服务返回，不把客户端声明当作授权来源。 */
    private String token(String role) {
        when(accounts.findById(1L))
                .thenReturn(
                        Optional.of(new UserIdentity(1L, "metrics-test", 0L, true, List.of(role))));
        when(sessions.isActive("00000000-0000-0000-0000-000000000001", 1L, 0L)).thenReturn(true);
        var claims =
                JwtClaimsSet.builder()
                        .subject("1")
                        .id("00000000-0000-0000-0000-000000000001")
                        .issuer(properties.getJwt().getIssuer())
                        .audience(List.of(properties.getJwt().getAudience()))
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(120))
                        .claim("credential_version", 0L)
                        .build();
        return "Bearer "
                + encoder.encode(
                                JwtEncoderParameters.from(
                                        JwsHeader.with(SignatureAlgorithm.RS256).build(), claims))
                        .getTokenValue();
    }
}
