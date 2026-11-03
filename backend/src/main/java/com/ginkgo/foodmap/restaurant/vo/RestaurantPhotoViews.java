package com.ginkgo.foodmap.restaurant.vo;

import lombok.Data;
import java.util.List;

/** 公开与管理端共用的正式图片白名单，不含存储键、原文件ID和投稿人身份。 */
public final class RestaurantPhotoViews {
    private RestaurantPhotoViews() { }

    @Data
    public static class Item {
        private String id;
        private int position;
        private boolean cover;
        private String url;
    }

    @Data
    public static class Page {
        private List<Item> records;
        private String nextCursor;
    }
}
