package com.ginkgo.foodmap.review.service;

import com.ginkgo.foodmap.common.exception.BusinessException;
import com.ginkgo.foodmap.file.exception.FileErrorCode;
import com.ginkgo.foodmap.file.service.MediaFileService;
import com.ginkgo.foodmap.review.mapper.ReviewPhotoMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

/** 图片读取由review验证内容与关联，再通过file公开服务读取私有对象。 */
@Service
@RequiredArgsConstructor
public class ReviewPhotoService {
    private final ReviewParentService parents;
    private final ReviewPhotoMapper photos;
    private final MediaFileService media;

    /** 每次读取重新验证父内容；云请求不置于数据库写事务内。 */
    public byte[] read(String review, String position) {
        long id;
        try {
            if (!review.matches("[1-9][0-9]{0,18}") || !position.matches("[0-4]")) {
                throw new IllegalArgumentException();
            }
            id = Long.parseLong(review);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(FileErrorCode.NOT_FOUND);
        }
        if (parents.visible(id) == null) {
            throw new BusinessException(FileErrorCode.NOT_FOUND);
        }
        var ids = photos.ids(id);
        int index = Integer.parseInt(position);
        if (index >= ids.size()) {
            throw new BusinessException(FileErrorCode.NOT_FOUND);
        }
        return media.readBound(ids.get(index));
    }
}
