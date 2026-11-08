package com.ginkgo.foodmap.restaurant.service;

import com.ginkgo.foodmap.common.exception.BusinessException;
import com.ginkgo.foodmap.restaurant.dto.RestaurantReviewDelta;
import com.ginkgo.foodmap.restaurant.exception.RestaurantErrorCode;
import com.ginkgo.foodmap.restaurant.mapper.RestaurantReviewStatisticsMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 面向review的公开协作入口；强制加入调用方事务，不建立独立提交或反向访问review表。 */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class RestaurantReviewApplicationService {
    private final RestaurantReviewStatisticsMapper mapper;
    private final CanteenWindowService windows;
    private final RestaurantSearchOutboxService searchOutbox;

    /** 与既有餐厅编辑/删除共用父行锁，锁内核验状态，不能仅依赖表单打开时检查。 */
    public void lockVisible(long id) {
        lockVisibleParent(id);
        mapper.initialize(id);
    }

    /** 普通餐厅发布入口；客户端绕过选窗口也不能直接新增食堂整体点评。 */
    public void lockPublishable(long id) {
        lockVisible(id);
        if (mapper.canteen(id) != 0) {
            throw new BusinessException(RestaurantErrorCode.STATE_CONFLICT);
        }
    }

    /** 回复只需要父内容互斥，不应为了回复创建或修改评分统计行。 */
    public void lockVisibleParent(long id) {
        if (mapper.lockRestaurant(id) == null || mapper.visible(id) != 1) {
            throw new BusinessException(RestaurantErrorCode.NOT_FOUND);
        }
    }

    /** 仅在当前事务持有该店锁时使用；更新异常交给外层整体回滚。 */
    public void applyDelta(long id, RestaurantReviewDelta delta) {
        if (mapper.canteen(id) != 0) {
            windows.refresh(id);
            return;
        }
        if (mapper.applyDelta(id, delta) != 1) {
            throw new IllegalStateException("Missing restaurant statistics");
        }
        // 与评价及统计一起提交/回滚；调用方此前已锁父行，保证每店版本按提交顺序递增。
        if (mapper.advanceSearchProjectionVersion(id) != 1) {
            throw new IllegalStateException(
                    "Restaurant search projection version unavailable or exhausted");
        }
        // review只调用本模块公开服务；评价、统计、版本与任务共用外层事务，失败一并回滚。
        searchOutbox.record(id);
    }
}
