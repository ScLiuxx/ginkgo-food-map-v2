package com.ginkgo.foodmap.system;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** 系统状态接口的安全边界测试，防止后续收紧认证规则时误将部署探针接口设为登录后可见。 */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(com.ginkgo.foodmap.security.TestJwtKeys.class)
class SystemStatusControllerTest {

    @Autowired private MockMvc mockMvc;

    /** 验证匿名请求能够访问状态接口，且响应仍遵循统一成功响应结构。 */
    @Test
    void publicStatusIsAccessibleWithoutLogin() throws Exception {
        mockMvc.perform(get("/api/public/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.message").value("请求成功"))
                .andExpect(jsonPath("$.data.architecture").value("modular-monolith"));
    }

    /** 验证匿名请求能够到达明确公开的验证码 POST 接口，而不是被安全过滤器提前返回 401。 */
    @Test
    void emailCodePostEndpointsArePublic() throws Exception {
        mockMvc.perform(
                        post("/auth/email/code/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"));
    }

    /** 验证匿名用户能够提交注册请求，空请求由参数校验返回400而不是认证过滤器返回401。 */
    @Test
    void registrationEndpointIsPublic() throws Exception {
        mockMvc.perform(
                        post("/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"));
    }

    /** 验证未显式公开的 GET 方法仍受保护，不能因路径相同而整体放行。 */
    @Test
    void emailCodeGetEndpointRemainsProtected() throws Exception {
        mockMvc.perform(get("/auth/email/code/register")).andExpect(status().isUnauthorized());
    }
}
