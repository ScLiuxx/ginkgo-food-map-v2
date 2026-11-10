package com.ginkgo.foodmap.security;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ginkgo.foodmap.security.service.LoginSessionService;
import com.ginkgo.foodmap.user.service.UserAccountService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/** 启用机器认证后复跑全部管理员JWT契约，并验证采集凭据的资源边界与基础设施隔离。 */
@SpringBootTest(
        properties = {
            "app.security.metrics.enabled=true",
            "app.security.metrics.password=test-only-metrics-password-32-characters"
        })
class MetricsScrapeEndpointTest extends PrometheusEndpointTest {
    private static final String PASSWORD = "test-only-metrics-password-32-characters";
    @Autowired private MockMvc mvc;
    @Autowired private UserAccountService accounts;
    @Autowired private LoginSessionService sessions;

    /** 不查询账号或会话，使数据库/Redis故障时仍然可以采集监控数据。 */
    @Test
    void machineCanScrapeWithoutBusinessAuthentication() throws Exception {
        mvc.perform(get("/actuator/prometheus").with(httpBasic("prometheus", PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("jvm_memory_used_bytes")));
        verifyNoInteractions(accounts, sessions);
    }

    /** 密码不匹配、账号不匹配及畸形凭据均不能读取指标。 */
    @Test
    void invalidMachineCredentialsAreRejected() throws Exception {
        mvc.perform(get("/actuator/prometheus").with(httpBasic("prometheus", "wrong")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/prometheus").with(httpBasic("admin", PASSWORD)))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/prometheus").header("Authorization", "Basic invalid!"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(accounts, sessions);
    }

    /** 机器身份没有管理员业务权限，也不能读取其他Actuator端点。 */
    @Test
    void machineCannotAccessBusinessOrOtherActuatorEndpoints() throws Exception {
        for (String path :
                new String[] {"/api/admin/me", "/auth/me", "/actuator/info", "/actuator/env"}) {
            mvc.perform(get(path).with(httpBasic("prometheus", PASSWORD)))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/restaurants").with(httpBasic("prometheus", PASSWORD)))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(accounts, sessions);
    }

    /** 不因认证成功而开放非GET采集请求或子路径。 */
    @Test
    void machineIsRestrictedToExactReadEndpoint() throws Exception {
        mvc.perform(post("/actuator/prometheus").with(httpBasic("prometheus", PASSWORD)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/actuator/prometheus/other").with(httpBasic("prometheus", PASSWORD)))
                .andExpect(status().isUnauthorized());
    }
}
