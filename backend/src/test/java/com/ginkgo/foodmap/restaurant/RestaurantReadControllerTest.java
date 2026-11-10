package com.ginkgo.foodmap.restaurant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.ginkgo.foodmap.common.exception.GlobalExceptionHandler;
import com.ginkgo.foodmap.restaurant.controller.RestaurantReadController;
import com.ginkgo.foodmap.restaurant.dto.RestaurantListQuery;
import com.ginkgo.foodmap.restaurant.service.RestaurantQueryService;
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

/** 快速协议测试：非法查询应在调用Service之前拒绝，不将分页或排序错误变成500。 */
class RestaurantReadControllerTest {
    private RestaurantReadService service;
    private MockMvc mvc;

    @BeforeEach
    void prepare() {
        service = mock(RestaurantReadService.class);
        mvc =
                MockMvcBuilders.standaloneSetup(
                                new RestaurantReadController(
                                        service,
                                        new RestaurantQueryService(
                                                service,
                                                mock(
                                                        org.springframework.beans.factory
                                                                .ObjectProvider.class),
                                                new com.ginkgo.foodmap.restaurant.config
                                                        .RestaurantSearchQueryProperties(),
                                                io.github.resilience4j.circuitbreaker.CircuitBreaker
                                                        .ofDefaults("controller-test"),
                                                new io.micrometer.core.instrument.simple
                                                        .SimpleMeterRegistry())))
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
    }

    /** 参数默认值沿用现有目录；客户端不能覆盖派生offset，最大页码计算不溢出。 */
    @Test
    void shouldBindDefaultsAndIgnoreInternalProperties() throws Exception {
        when(service.list(any())).thenReturn(new RestaurantPageVo());
        mvc.perform(get("/api/restaurants").param("offset", "10000").param("keywordPattern", "%"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"));
        var query = ArgumentCaptor.forClass(RestaurantListQuery.class);
        verify(service).list(query.capture());
        assertThat(query.getValue().getPageSize()).isEqualTo(8);
        assertThat(query.getValue().getOffset()).isZero();
        assertThat(query.getValue().getSort()).isEqualTo("score");
    }

    /** 白名单、类型转换和分页上下界统一返回字段错误，不进入数据库查询。 */
    @ParameterizedTest
    @CsvSource({
        "page,0",
        "page,-1",
        "page,abc",
        "page,2147483648",
        "pageSize,0",
        "pageSize,101",
        "pageSize,abc",
        "hideClosed,maybe",
        "sort,id desc",
        "sort,PRICE",
        "category,other",
        "category,'OTHER '",
        "sort,taste",
        "sort,value",
        "sort,service",
        "sort,ambience"
    })
    void shouldRejectInvalidQuery(String field, String value) throws Exception {
        mvc.perform(get("/api/restaurants").param(field, value))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"));
        verifyNoInteractions(service);
    }

    /** 大页码偏移不溢出，但首页与关键词列表统一拒绝超出ES窗口的请求。 */
    @Test
    void shouldRejectCatalogOverflowWithoutQueryingSql() throws Exception {
        mvc.perform(get("/api/restaurants").param("page", "2147483647").param("pageSize", "100"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
        var query = new RestaurantListQuery();
        query.setPage(Integer.MAX_VALUE);
        query.setPageSize(100);
        assertThat(query.getOffset()).isEqualTo(214748364600L);
    }

    /** 相关度可显式请求，实际SQL能力与分页上限可观察；内部排序属性不能由HTTP覆盖。 */
    @Test
    void shouldAcceptRelevanceAndRejectSearchWindowOverflowBeforeSql() throws Exception {
        when(service.list(any())).thenReturn(new RestaurantPageVo());
        mvc.perform(
                        get("/api/restaurants")
                                .param("keyword", "清水河咖啡")
                                .param("sort", "relevance")
                                .param("searchOrderById", "true")
                                .param("effectiveSort", "price"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.search.effectiveSort").value("score"))
                .andExpect(jsonPath("$.data.search.reason").value("DISABLED"));
        var query = ArgumentCaptor.forClass(RestaurantListQuery.class);
        verify(service).list(query.capture());
        assertThat(query.getValue().isSearchOrderById()).isFalse();
        clearInvocations(service);
        mvc.perform(get("/api/restaurants").param("keyword", "咖啡").param("page", "1251"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RESTAURANT_INVALID_INPUT"));
        verifyNoInteractions(service);
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

    /** 两个公开入口都绑定新条件并经过统一查询服务，不影响字典及详情路由。 */
    @Test
    void shouldBindDiningFiltersForBothListAndMap() throws Exception {
        mvc.perform(
                        get("/api/restaurants")
                                .param("restaurantType", "CANTEEN")
                                .param("diningMode", "dineIn"))
                .andExpect(status().isOk());
        var query = ArgumentCaptor.forClass(RestaurantListQuery.class);
        verify(service).list(query.capture());
        assertThat(query.getValue().getRestaurantType()).isEqualTo("CANTEEN");
        assertThat(query.getValue().getDiningMode()).isEqualTo("dineIn");
        mvc.perform(
                        get("/api/restaurants/map-points")
                                .param("restaurantType", "REGULAR")
                                .param("diningMode", "delivery")
                                .param("west", "103")
                                .param("east", "105")
                                .param("south", "30")
                                .param("north", "32"))
                .andExpect(status().isOk());
        verify(service).mapPoints(query.capture());
        assertThat(query.getValue().getRestaurantType()).isEqualTo("REGULAR");
        assertThat(query.getValue().getDiningMode()).isEqualTo("delivery");
    }

    /** 不合法类型/用餐方式不能被忽略后返回全部，两个公开入口使用相同校验。 */
    @ParameterizedTest
    @CsvSource({
        "restaurantType,ALL",
        "restaurantType,regular",
        "restaurantType,CANTEEN_",
        "diningMode,DINE_IN",
        "diningMode,true",
        "diningMode,unknown",
        "diningMode,dinein"
    })
    void shouldRejectInvalidDiningFilters(String field, String value) throws Exception {
        for (String path : java.util.List.of("/api/restaurants", "/api/restaurants/map-points"))
            mvc.perform(get(path).param(field, value)).andExpect(status().isBadRequest());
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
