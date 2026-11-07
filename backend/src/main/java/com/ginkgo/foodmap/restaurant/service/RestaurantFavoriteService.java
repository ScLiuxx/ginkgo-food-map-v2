package com.ginkgo.foodmap.restaurant.service;

import com.ginkgo.foodmap.common.exception.BusinessException;
import com.ginkgo.foodmap.restaurant.dto.FavoriteListQuery;
import com.ginkgo.foodmap.restaurant.exception.RestaurantErrorCode;
import com.ginkgo.foodmap.restaurant.mapper.RestaurantFavoriteMapper;
import com.ginkgo.foodmap.restaurant.mapper.RestaurantWriteMapper;
import com.ginkgo.foodmap.restaurant.support.RestaurantIdentifiers;
import com.ginkgo.foodmap.restaurant.vo.FavoriteViews;
import com.ginkgo.foodmap.user.service.FavoritePrivacyService;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** 店铺收藏应用流程；收藏写入与商户删除共用主行锁，不联查user表。 */
@Service
@RequiredArgsConstructor
public class RestaurantFavoriteService {
    private final RestaurantFavoriteMapper favorites;
    private final RestaurantWriteMapper restaurants;
    private final RestaurantReadService visible;
    private final FavoritePrivacyService privacy;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public FavoriteViews.State state(long user, String value) {
        long id = RestaurantIdentifiers.id(value);
        visible.requireVisible(id);
        return state(id, favorites.contains(user, id));
    }

    /** 收藏只接受可见店铺；取消允许清理失效关系且重复取消成功，身份始终由控制器提供。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public FavoriteViews.State set(long user, String value, boolean active) {
        long id = RestaurantIdentifiers.id(value);
        var target = restaurants.lockById(id);
        if (active && (target == null || target.getDeletedAt() != null)) {
            throw new BusinessException(RestaurantErrorCode.NOT_FOUND);
        }
        if (active) {
            favorites.add(user, id);
        } else {
            favorites.remove(user, id);
        }
        return state(id, active);
    }

    /** 每次读取在同一快照核验隐私、可见性、总数及记录；不先分页再过滤失效项。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public FavoriteViews.Page list(long viewer, long user, FavoriteListQuery query) {
        if (!privacy.canView(user, viewer)) {
            throw new BusinessException(RestaurantErrorCode.FAVORITES_PRIVATE);
        }
        boolean own = viewer == user;
        long total = favorites.count(user, own);
        var result = new FavoriteViews.Page();
        result.setUserId(Long.toString(user));
        result.setTotal(total);
        result.setPage(query.getPage());
        result.setPageSize(query.getPageSize());
        result.setTotalPages(
                total / query.getPageSize() + (total % query.getPageSize() == 0 ? 0 : 1));
        if (query.getOffset() < total) {
            result.setRecords(favorites.page(user, own, query.getPageSize(), query.getOffset()));
        }
        return result;
    }

    private FavoriteViews.State state(long id, boolean active) {
        var result = new FavoriteViews.State();
        result.setRestaurantId(Long.toString(id));
        result.setFavorited(active);
        return result;
    }
}
