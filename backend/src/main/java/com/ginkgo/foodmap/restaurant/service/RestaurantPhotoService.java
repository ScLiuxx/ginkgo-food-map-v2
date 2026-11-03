package com.ginkgo.foodmap.restaurant.service;

import com.ginkgo.foodmap.common.exception.BusinessException;
import com.ginkgo.foodmap.file.service.MediaFileService;
import com.ginkgo.foodmap.restaurant.dto.RestaurantPhotoQuery;
import com.ginkgo.foodmap.restaurant.entity.RestaurantPhotoEntity;
import com.ginkgo.foodmap.restaurant.exception.RestaurantErrorCode;
import com.ginkgo.foodmap.restaurant.mapper.RestaurantPhotoMapper;
import com.ginkgo.foodmap.restaurant.support.RestaurantIdentifiers;
import com.ginkgo.foodmap.restaurant.vo.RestaurantPhotoViews;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 正式相册公开读取；仅关系/元数据查询进入只读事务，OSS读取不占数据库事务或行锁。 */
@Service
@RequiredArgsConstructor
public class RestaurantPhotoService {
    private final RestaurantPhotoMapper photos;
    private final MediaFileService files;

    /** 仅批量查询当前页封面元数据，不访问OSS；复用调用方事务保持封面与目录的快照一致。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<Long, RestaurantPhotoViews.Item> covers(List<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        if (ids.size() > 100) throw new BusinessException(RestaurantErrorCode.INVALID_INPUT);
        return photos.covers(ids).stream().collect(Collectors.toMap(RestaurantPhotoEntity::getRestaurantId, RestaurantPhotoService::view));
    }

    /** 同一快照验证父商户、游标和有界列表；空相册200，越权/不存在游标404。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public RestaurantPhotoViews.Page page(String id, RestaurantPhotoQuery query) {
        if (query == null || query.getLimit() == null || query.getLimit() < 1 || query.getLimit() > 50)
            throw new BusinessException(RestaurantErrorCode.INVALID_INPUT);
        long restaurant = RestaurantIdentifiers.id(id);
        if (photos.visibleRestaurant(restaurant) == null) throw new BusinessException(RestaurantErrorCode.NOT_FOUND);
        int after = -1;
        if (query.getCursor() != null) {
            Integer position = photos.cursorPosition(restaurant, RestaurantIdentifiers.id(query.getCursor()));
            if (position == null) throw new BusinessException(RestaurantErrorCode.PHOTO_NOT_FOUND);
            after = position;
        }
        var rows = photos.page(restaurant, after, query.getLimit() + 1);
        boolean more = rows.size() > query.getLimit();
        List<RestaurantPhotoEntity> visible = more ? rows.subList(0, query.getLimit()) : rows;
        var result = new RestaurantPhotoViews.Page();
        result.setRecords(visible.stream().map(RestaurantPhotoService::view).toList());
        result.setNextCursor(more ? visible.getLast().getId().toString() : null);
        return result;
    }

    /** 每次从当前可见商户与正式关联解析引用，再校验原上传人/用途/状态，禁止任意文件ID访问。 */
    public byte[] photo(String id, String photoId) {
        var reference = photos.file(RestaurantIdentifiers.id(id), RestaurantIdentifiers.id(photoId));
        if (reference == null) throw new BusinessException(RestaurantErrorCode.PHOTO_NOT_FOUND);
        return files.readPublishedRestaurant(reference.getMediaFileId(), reference.getSubmittedBy());
    }

    /** 固定本站受控地址及稳定关联ID；封面只由商户内position=0确定，与分页起点无关。 */
    public static RestaurantPhotoViews.Item view(RestaurantPhotoEntity row) {
        var result = new RestaurantPhotoViews.Item(); result.setId(row.getId().toString());
        result.setPosition(row.getPosition()); result.setCover(row.getPosition() == 0);
        result.setUrl("/api/restaurants/" + row.getRestaurantId() + "/photos/" + row.getId());
        return result;
    }
}
