package com.ginkgo.foodmap.restaurant;

import com.ginkgo.foodmap.common.exception.GlobalExceptionHandler;
import com.ginkgo.foodmap.restaurant.controller.RestaurantReadController;
import com.ginkgo.foodmap.restaurant.dto.RestaurantListQuery;
import com.ginkgo.foodmap.restaurant.service.RestaurantReadService;
import com.ginkgo.foodmap.restaurant.vo.RestaurantPageVo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 快速协议测试：非法查询应在调用Service之前拒绝，不将分页或排序错误变成500。 */
class RestaurantReadControllerTest {
    private RestaurantReadService service;
    private MockMvc mvc;

    @BeforeEach
    void prepare() {
        service = mock(RestaurantReadService.class);
        mvc = MockMvcBuilders.standaloneSetup(new RestaurantReadController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    /** 参数默认值沿用现有目录；客户端不能覆盖派生offset，最大页码计算不溢出。 */
    @Test
    void shouldBindDefaultsAndIgnoreInternalProperties() throws Exception {
        when(service.list(any())).thenReturn(new RestaurantPageVo());
        mvc.perform(get("/api/restaurants").param("offset", "10000").param("keywordPattern", "%"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("OK"));
        var query = ArgumentCaptor.forClass(RestaurantListQuery.class);
        verify(service).list(query.capture());
        assertThat(query.getValue().getPageSize()).isEqualTo(8);
        assertThat(query.getValue().getOffset()).isZero();
        assertThat(query.getValue().getSort()).isEqualTo("score");
    }

    /** 白名单、类型转换和分页上下界统一返回字段错误，不进入数据库查询。 */
    @ParameterizedTest
    @CsvSource({"page,0", "page,-1", "page,abc", "page,2147483648", "pageSize,0", "pageSize,101",
            "pageSize,abc", "hideClosed,maybe", "sort,id desc", "sort,PRICE",
            "category,other", "category,'OTHER '", "sort,taste", "sort,value", "sort,service", "sort,ambience"})
    void shouldRejectInvalidQuery(String field, String value) throws Exception {
        mvc.perform(get("/api/restaurants").param(field, value))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"));
        verifyNoInteractions(service);
    }

    /** 中文搜索及最大合法页码可以绑定，且偏移量仍为正long。 */
    @Test
    void shouldBindSearchWithoutOverflow() throws Exception {
        mvc.perform(get("/api/restaurants")
                        .param("keyword", "  火锅  ").param("page", "2147483647").param("pageSize", "100"))
                .andExpect(status().isOk());
        var query = ArgumentCaptor.forClass(RestaurantListQuery.class);
        verify(service).list(query.capture());
        assertThat(query.getValue().getOffset()).isEqualTo(214748364600L);
    }

    /** 已移除的参数明确报错，不能被悄悄忽略后返回全部商户。 */
    @ParameterizedTest
    @ValueSource(strings = {"tiers", "tiers[]", "tiers[0]", "view"})
    void shouldRejectRemovedClassificationQueries(String field) throws Exception {
        mvc.perform(get("/api/restaurants").param(field, "none"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RESTAURANT_INVALID_INPUT"));
        verifyNoInteractions(service);
    }

    /** 防止超长关键词扩大单次请求成本。 */
    @Test
    void shouldBoundSearchInputs() throws Exception {
        mvc.perform(get("/api/restaurants").param("keyword", "食".repeat(101)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
