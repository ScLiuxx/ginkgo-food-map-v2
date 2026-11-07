package com.ginkgo.foodmap.restaurant;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.ginkgo.foodmap.common.exception.BusinessException;
import com.ginkgo.foodmap.restaurant.dto.RestaurantListQuery;
import com.ginkgo.foodmap.restaurant.entity.RestaurantEntity;
import com.ginkgo.foodmap.restaurant.entity.RestaurantTagEntity;
import com.ginkgo.foodmap.restaurant.mapper.RestaurantCatalogMapper;
import com.ginkgo.foodmap.restaurant.service.RestaurantReadService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

/** 应用服务边界测试：空页不查询标签，ID不截断，公开评分空态保持null。 */
class RestaurantReadServiceTest {
    private final RestaurantCatalogMapper mapper = mock(RestaurantCatalogMapper.class);
    private final com.ginkgo.foodmap.restaurant.service.RestaurantPhotoService albums =
            mock(com.ginkgo.foodmap.restaurant.service.RestaurantPhotoService.class);
    private final RestaurantReadService service = new RestaurantReadService(mapper, albums);

    /** 无结果与越界页都返回正确元数据，且不执行无效的IN空集合或额外分页SQL。 */
    @Test
    void shouldPreserveTotalsForOutOfRangePage() {
        var query = new RestaurantListQuery();
        query.setPage(Integer.MAX_VALUE);
        query.setPageSize(100);
        when(mapper.countVisible(query)).thenReturn(101L);
        var page = service.list(query);
        assertThat(page.getRecords()).isEmpty();
        assertThat(page.getTotal()).isEqualTo(101);
        assertThat(page.getTotalPages()).isEqualTo(2);
        assertThat(page.getPage()).isEqualTo(Integer.MAX_VALUE);
        verify(mapper).countVisible(query);
        verifyNoMoreInteractions(mapper);
    }

    /** 详情ID必须是可表示的规范正整数，不允许溢出、前导零或非数字格式。 */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "0",
                "-1",
                "01",
                "1.0",
                "r01",
                "9223372036854775808",
                "99999999999999999999"
            })
    void shouldRejectInvalidIdBeforeReading(String id) {
        assertThatThrownBy(() -> service.detail(id))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error ->
                                assertThat(error.getErrorCode().getCode())
                                        .isEqualTo("RESTAURANT_INVALID_ID"));
        verifyNoInteractions(mapper);
    }

    /** 数据库可见查询为空时直接404，不读取任何残留历史标签。 */
    @Test
    void shouldHideMissingAndDeletedRestaurants() {
        assertThatThrownBy(() -> service.detail("1"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error ->
                                assertThat(error.getErrorCode().getCode())
                                        .isEqualTo("RESTAURANT_NOT_FOUND"));
        verify(mapper).selectVisibleById(1);
        verifyNoMoreInteractions(mapper);
    }

    /** 超过JS安全整数的ID按字符串返回；标签保序，未知价格和评分不补0。 */
    @Test
    void shouldExposeStringIdAndUnratedState() {
        var row = new RestaurantEntity();
        row.setId(9007199254740993L);
        row.setName("测试夹具");
        when(mapper.selectVisibleById(row.getId())).thenReturn(row);
        var tag = new RestaurantTagEntity();
        tag.setRestaurantId(row.getId());
        tag.setTag("测试标签");
        when(mapper.selectVisibleTags(List.of(row.getId()))).thenReturn(List.of(tag));
        var vo = service.detail(row.getId().toString());
        assertThat(vo.getId()).isEqualTo("9007199254740993");
        assertThat(vo.getTags()).containsExactly("测试标签");
        assertThat(vo.getScore()).isNull();
        assertThat(vo.getDimensions().getTaste()).isNull();
        assertThat(vo.getRecommendRate()).isNull();
        assertThat(vo.getReviewCount()).isZero();
        assertThat(vo.getPricePerPerson()).isNull();
    }

    /** 封面按当前页ID集合批量补齐，不逐条查询，不将无相册商户的null补成其他图片。 */
    @Test
    void shouldBatchCoverMetadataForCurrentPage() {
        var query = new RestaurantListQuery();
        var first = new RestaurantEntity();
        first.setId(1L);
        first.setName("有图商户");
        var second = new RestaurantEntity();
        second.setId(2L);
        second.setName("无图商户");
        var cover = new com.ginkgo.foodmap.restaurant.vo.RestaurantPhotoViews.Item();
        cover.setId("801");
        cover.setPosition(0);
        cover.setCover(true);
        cover.setUrl("/api/restaurants/1/photos/801");
        when(mapper.countVisible(query)).thenReturn(2L);
        when(mapper.selectVisiblePage(query)).thenReturn(List.of(first, second));
        when(albums.covers(List.of(1L, 2L))).thenReturn(java.util.Map.of(1L, cover));
        var result = service.list(query);
        assertThat(result.getRecords().get(0).getCoverPhoto().getUrl())
                .isEqualTo("/api/restaurants/1/photos/801");
        assertThat(result.getRecords().get(1).getCoverPhoto()).isNull();
        verify(albums, times(1)).covers(List.of(1L, 2L));
        verifyNoMoreInteractions(albums);
    }
}
