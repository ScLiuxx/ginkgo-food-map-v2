package com.foodmap.restaurant.service;

import com.foodmap.common.exception.BusinessException;
import com.foodmap.file.service.MediaFileService;
import com.foodmap.restaurant.dto.RestaurantPhotoSelection;
import com.foodmap.restaurant.entity.RestaurantPhotoEntity;
import com.foodmap.restaurant.exception.RestaurantErrorCode;
import com.foodmap.restaurant.mapper.RestaurantPhotoMapper;
import com.foodmap.restaurant.support.RestaurantIdentifiers;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** 商户主行锁内整份替换相册；保留关联ID，媒体绑定/释放及资料保存同事务。 */
@Service
@RequiredArgsConstructor
public class RestaurantPhotoManagementService {
    private final RestaurantPhotoMapper photos;
    private final MediaFileService media;

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(propagation = Propagation.MANDATORY)
    public void replace(long restaurant, long actor, List<RestaurantPhotoSelection> selection) {
        if (selection == null) {
            return;
        }
        var existing = photos.all(restaurant);
        var replacement = new ArrayList<RestaurantPhotoEntity>();
        var used = new HashSet<Long>();
        var bind = new ArrayList<Long>();
        for (var item : selection) {
            RestaurantPhotoEntity row;
            if (item.getPhotoId() != null) {
                long id = RestaurantIdentifiers.id(item.getPhotoId());
                row =
                        existing.stream()
                                .filter(p -> p.getId() == id)
                                .findFirst()
                                .orElseThrow(this::conflict);
            } else {
                long file = RestaurantIdentifiers.id(item.getFileId());
                // 成功响应丢失时，同一已绑定文件仅可重用当前店、当前管理员自己的关联。
                row =
                        existing.stream()
                                .filter(
                                        p ->
                                                p.getMediaFileId() == file
                                                        && Objects.equals(p.getUploadedBy(), actor))
                                .findFirst()
                                .orElse(null);
                if (row == null) {
                    row = new RestaurantPhotoEntity();
                    row.setRestaurantId(restaurant);
                    row.setMediaFileId(file);
                    row.setUploadedBy(actor);
                    bind.add(file);
                }
            }
            if (!used.add(row.getMediaFileId())) {
                throw conflict();
            }
            replacement.add(row);
        }
        media.bindRestaurantPhotos(bind, actor);
        // 用户投稿图与历史申请共享BOUND文件，只删除正式关联；管理员图没有共享申请，登记物理清理。
        for (var row :
                existing.stream()
                        .sorted(
                                java.util.Comparator.comparing(
                                        RestaurantPhotoEntity::getMediaFileId))
                        .toList()) {
            if (!used.contains(row.getMediaFileId()) && row.getUploadedBy() != null) {
                media.releaseRestaurantPhotos(List.of(row.getMediaFileId()), row.getUploadedBy());
            }
        }
        // 删除再插入在同一事务完成，避免唯一位置索引交换封面时发生冲突；已有ID和创建时间保持。
        photos.clear(restaurant);
        for (int position = 0; position < replacement.size(); position++) {
            var row = replacement.get(position);
            row.setPosition(position);
            photos.insertManaged(row);
        }
    }

    private BusinessException conflict() {
        return new BusinessException(RestaurantErrorCode.STATE_CONFLICT, "相册已变化或照片不属于当前商户，请重新读取");
    }
}
