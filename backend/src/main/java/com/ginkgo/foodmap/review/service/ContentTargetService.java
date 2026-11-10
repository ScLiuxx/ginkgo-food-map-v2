package com.ginkgo.foodmap.review.service;

import com.ginkgo.foodmap.restaurant.service.CanteenWindowService;
import com.ginkgo.foodmap.restaurant.service.RestaurantReviewTargetService;
import com.ginkgo.foodmap.review.dto.ContentTargetSnapshot;
import com.ginkgo.foodmap.review.mapper.ContentLikeMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 跨模块公开的点评/回复目标读取服务。
 *
 * <p>复用点赞模块已有的目标查询SQL，避免重复维护点评与回复两套来源查询；可见性仍由餐厅和
 * 食堂窗口的公开服务逐次核验，不能因为目标行存在就假定用户可访问。
 */
@Service
@RequiredArgsConstructor
public class ContentTargetService {
    private final ContentLikeMapper targets;
    private final RestaurantReviewTargetService restaurants;
    private final CanteenWindowService windows;

    /**
     * 返回当前可见目标的举报所需快照；目标不存在或父店不可见时返回 {@code null}。
     *
     * <p>调用方负责把 {@code null} 转成对外统一的不存在错误，避免把内部可见性细节暴露给客户端。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ContentTargetSnapshot findVisible(String type, long id) {
        if (!"REVIEW".equals(type) && !"REPLY".equals(type)) {
            return null;
        }
        var rows = targets.targets(type, List.of(id));
        if (rows.isEmpty()) {
            return null;
        }
        var row = rows.getFirst();
        boolean windowTarget = row.getWindowId() != null;
        if (windowTarget) {
            if (!windows.targets(List.of(row.getWindowId())).containsKey(row.getWindowId())) {
                return null;
            }
        } else if (row.getRestaurantId() == null
                || restaurants.visible(List.of(row.getRestaurantId())).isEmpty()) {
            return null;
        }
        var result = new ContentTargetSnapshot();
        result.setTargetType(type);
        result.setTargetId(id);
        result.setReviewId(row.getReviewId());
        result.setAuthorUserId(row.getAuthorUserId());
        result.setBody(row.getBody());
        result.setWindowTarget(windowTarget);
        return result;
    }
}
