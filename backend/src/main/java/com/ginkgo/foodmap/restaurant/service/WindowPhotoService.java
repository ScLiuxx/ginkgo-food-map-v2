package com.ginkgo.foodmap.restaurant.service;

import com.ginkgo.foodmap.common.exception.BusinessException;
import com.ginkgo.foodmap.file.service.MediaFileService;
import com.ginkgo.foodmap.restaurant.exception.RestaurantErrorCode;
import com.ginkgo.foodmap.restaurant.mapper.WindowPhotoMapper;
import com.ginkgo.foodmap.restaurant.support.RestaurantIdentifiers;
import com.ginkgo.foodmap.restaurant.vo.WindowPhotoView;
import com.ginkgo.foodmap.security.service.CurrentActorService;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 管理员维护窗口相册，公开读取每次核对窗口及父食堂；OSS调用不进入写事务。 */
@Service
@RequiredArgsConstructor
public class WindowPhotoService {
    private final CanteenWindowService windows;
    private final WindowPhotoMapper photos;
    private final MediaFileService media;
    private final CurrentActorService actors;

    /** 相册最多五张，公开列表不包含任何未绑定临时文件。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<WindowPhotoView> list(String id) {
        long window = RestaurantIdentifiers.id(id);
        windows.requireVisible(window);
        return views(window);
    }

    /** 相同窗口、相同上传回执重试幂等，不重复绑定或增加图片数量。 */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<WindowPhotoView> add(String id, String fileId) {
        long window = RestaurantIdentifiers.id(id), file = RestaurantIdentifiers.id(fileId);
        windows.lockVisible(window, false);
        var existing = photos.list(window);
        if (existing.contains(file)) {
            return views(window);
        }
        if (existing.size() >= 5) {
            throw new BusinessException(RestaurantErrorCode.STATE_CONFLICT);
        }
        long actor = actors.requireActor().getUserId();
        media.bindWindowPhoto(file, actor);
        photos.insert(window, file);
        updateCover(window, actor);
        return views(window);
    }

    /** 移除关联、更新封面及提交回收意图同事务完成，云端失败由后台重试。 */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void remove(String id, String fileId) {
        long window = RestaurantIdentifiers.id(id), file = RestaurantIdentifiers.id(fileId);
        windows.lockVisible(window, false);
        if (photos.delete(window, file) == 0) {
            return;
        }
        media.releaseWindowPhoto(file);
        updateCover(window, actors.requireActor().getUserId());
    }

    /** 不按裸文件ID读取，必须同时匹配当前窗口相册和父食堂可见性。 */
    public byte[] read(String id, String fileId) {
        long window = RestaurantIdentifiers.id(id), file = RestaurantIdentifiers.id(fileId);
        windows.requireVisible(window);
        if (!photos.list(window).contains(file)) {
            throw new BusinessException(RestaurantErrorCode.NOT_FOUND);
        }
        return media.readWindowPhoto(file);
    }

    private void updateCover(long window, long actor) {
        var ids = photos.list(window);
        photos.cover(window, ids.isEmpty() ? null : ids.getFirst(), actor);
    }

    private List<WindowPhotoView> views(long window) {
        var ids = photos.list(window);
        return ids.stream()
                .map(
                        id -> {
                            var item = new WindowPhotoView();
                            item.setId(id.toString());
                            item.setUrl("/api/canteen-windows/" + window + "/photos/" + id);
                            item.setCover(id.equals(ids.getFirst()));
                            return item;
                        })
                .toList();
    }
}
