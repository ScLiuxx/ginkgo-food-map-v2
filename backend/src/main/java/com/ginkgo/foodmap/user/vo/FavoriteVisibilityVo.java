package com.ginkgo.foodmap.user.vo;
import lombok.Data;
/** 本人的收藏隐私设置，不包含账号资料或任何收藏者名单。 */
@Data
public class FavoriteVisibilityVo {
    private String userId;
    private boolean favoritesPublic;
}
