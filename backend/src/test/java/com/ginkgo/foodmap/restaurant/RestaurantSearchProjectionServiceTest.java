package com.ginkgo.foodmap.restaurant;

import com.ginkgo.foodmap.restaurant.entity.RestaurantTagEntity;
import com.ginkgo.foodmap.restaurant.mapper.RestaurantCatalogMapper;
import com.ginkgo.foodmap.restaurant.mapper.RestaurantSearchProjectionMapper;
import com.ginkgo.foodmap.restaurant.service.RestaurantPhotoService;
import com.ginkgo.foodmap.restaurant.service.RestaurantSearchProjectionService;
import com.ginkgo.foodmap.restaurant.vo.RestaurantPhotoViews;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 批次行为测试：三次有界批量读取、来源版本、缺失失败，不对软删除补读旧资料。 */
class RestaurantSearchProjectionServiceTest {
    private final RestaurantSearchProjectionMapper mapper = mock(RestaurantSearchProjectionMapper.class);
    private final RestaurantCatalogMapper catalog = mock(RestaurantCatalogMapper.class);
    private final RestaurantPhotoService albums = mock(RestaurantPhotoService.class);
    private final RestaurantSearchProjectionService service = new RestaurantSearchProjectionService(mapper, catalog, albums);

    @Test void batchesOnlyVisibleTagsAndCoversAndKeepsRequestedOrder() {
        var visible = RestaurantSearchProjectionPolicyTest.regular(); visible.setId(2L);
        var deleted = RestaurantSearchProjectionPolicyTest.regular(); deleted.setId(1L); deleted.setDeleted(true);
        visible.setProjectionVersion(8L); deleted.setProjectionVersion(9L);
        when(mapper.selectSources(List.of(2L, 1L))).thenReturn(List.of(deleted, visible));
        var tag = new RestaurantTagEntity(); tag.setRestaurantId(2L); tag.setTag("学生聚餐");
        when(catalog.selectVisibleTags(List.of(2L))).thenReturn(List.of(tag));
        var cover = new RestaurantPhotoViews.Item(); cover.setId("9007199254740993");
        when(albums.covers(List.of(2L))).thenReturn(Map.of(2L, cover));
        var result = service.assemble(List.of(2L, 1L, 2L));
        assertThat(result).extracting("id").containsExactly(2L, 1L);
        assertThat(result.getFirst().getTags()).containsExactly("学生聚餐");
        assertThat(result.getFirst().getCoverPhotoId()).isEqualTo("9007199254740993");
        assertThat(result.getLast().getDeleted()).isTrue();
        assertThat(result.getLast().getProjectionVersion()).isEqualTo(9);
        verify(mapper).selectSources(List.of(2L, 1L));
        verify(catalog).selectVisibleTags(List.of(2L)); verify(albums).covers(List.of(2L));
        verifyNoMoreInteractions(mapper, catalog, albums);
    }

    @Test void emptyAndInvalidBatchesDoNotReadSources() {
        assertThat(service.assemble(List.of())).isEmpty();
        assertThatIllegalArgumentException().isThrownBy(() -> service.assemble(List.of(0L)));
        var tooMany = new ArrayList<Long>(); for (long id = 1; id <= 101; id++) tooMany.add(id);
        assertThatIllegalArgumentException().isThrownBy(() -> service.assemble(tooMany));
        assertThatIllegalArgumentException().isThrownBy(() -> service.assemble(null));
        verifyNoInteractions(mapper, catalog, albums);
    }

    @Test void missingIsNotInterpretedAsDeletionAndDeletedBatchSkipsEnrichment() {
        when(mapper.selectSources(List.of(1L))).thenReturn(List.of());
        assertThatIllegalStateException().isThrownBy(() -> service.assemble(List.of(1L)));
        verifyNoInteractions(catalog, albums);
        var deleted = RestaurantSearchProjectionPolicyTest.regular(); deleted.setId(1L); deleted.setDeleted(true); deleted.setProjectionVersion(2L);
        when(mapper.selectSources(List.of(1L))).thenReturn(List.of(deleted));
        assertThat(service.assemble(List.of(1L)).getFirst().getDeleted()).isTrue();
        verifyNoInteractions(catalog, albums);
    }
}
