package com.foodmap.review.entity;

import lombok.Data;

/** 仅读取review自有照片关联；正式URL使用点评与位置，不读取或暴露OSS信息。 */
@Data
public class ReviewPhotoReferenceEntity {
    private long reviewId;
    private long mediaFileId;
    private int position;
}
