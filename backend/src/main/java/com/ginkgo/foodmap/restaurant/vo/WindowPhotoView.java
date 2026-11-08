package com.ginkgo.foodmap.restaurant.vo;

import lombok.Data;

/** 只返回相册关联ID、是否封面和受窗口可见性保护的图片路径。 */
@Data
public class WindowPhotoView {
    private String id;
    private String url;
    private boolean cover;
}
