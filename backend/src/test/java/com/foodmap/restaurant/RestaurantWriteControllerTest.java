package com.foodmap.restaurant;

import com.foodmap.common.exception.GlobalExceptionHandler;
import com.foodmap.restaurant.controller.RestaurantWriteController;
import com.foodmap.restaurant.dto.RestaurantSaveRequest;
import com.foodmap.restaurant.service.RestaurantWriteService;
import com.foodmap.restaurant.vo.RestaurantManagementVo;
import com.foodmap.restaurant.vo.RestaurantVo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 默认运行的写入协议测试，不依赖MySQL；权限和事务由显式启用的真实集成测试覆盖。 */
class RestaurantWriteControllerTest {
    private RestaurantWriteService service;
    private MockMvc mvc;
    private static final String VALID = """
            {"name":"测试夹具","categoryCode":"OTHER","latitude":30.7544,"longitude":103.9312,
             "locationConfirmed":true,"priceKind":"UNKNOWN","tags":[]}
            """;

    @BeforeEach
    void prepare() {
        service = mock(RestaurantWriteService.class);
        mvc = MockMvcBuilders.standaloneSetup(new RestaurantWriteController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    /** 成功创建使用201、Location及禁止缓存标头，不返回令牌或账号身份。 */
    @Test
    void shouldReturnCreatedLocationAndNoStore() throws Exception {
        var result = new RestaurantManagementVo();
        var restaurant = new RestaurantVo(); restaurant.setId("101");
        result.setRestaurant(restaurant);
        when(service.create(any())).thenReturn(result);
        mvc.perform(post("/api/restaurants").contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isCreated()).andExpect(header().string("Location", "/api/restaurants/101"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.rowVersion").doesNotExist());
        var captured = ArgumentCaptor.forClass(RestaurantSaveRequest.class);
        verify(service).create(captured.capture());
        assertThat(captured.getValue().getPricePerPerson()).isNull();
    }

    /** 不完整、错误编码、错误确认及空标签会在进入应用服务前返回400。 */
    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "{", "[]",
            "{\"name\":\"测试\",\"categoryCode\":\"OTHER\",\"latitude\":30,\"longitude\":103,\"locationConfirmed\":false}",
            "{\"name\":\"测试\",\"categoryCode\":\"other\",\"latitude\":30,\"longitude\":103,\"locationConfirmed\":true}",
            "{\"name\":\"测试\",\"categoryCode\":\"OTHER\",\"latitude\":30,\"longitude\":103,\"locationConfirmed\":true,\"tags\":[null]}"})
    void shouldRejectMalformedAndInvalidInputs(String body) throws Exception {
        mvc.perform(post("/api/restaurants").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    /** 身份、来源、评分等未知字段不得静默忽略。 */
    @Test
    void shouldRejectUnexpectedBodyFields() throws Exception {
        mvc.perform(post("/api/restaurants").contentType(MediaType.APPLICATION_JSON)
                        .content(VALID.replace("\"name\"", "\"createdBy\":22,\"name\"")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON_MALFORMED_REQUEST"));
        verifyNoInteractions(service);
    }

    /** 删除不要求版本或请求体，管理响应继续禁止缓存。 */
    @Test
    void shouldDeleteWithoutVersion() throws Exception {
        mvc.perform(delete("/api/restaurants/101"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        verify(service).delete("101");
    }

    /** 下线的恢复地址不再进入写服务。 */
    @Test
    void shouldNotExposeRestoreEndpoint() throws Exception {
        mvc.perform(post("/api/restaurants/101/restore"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(service);
    }
}
