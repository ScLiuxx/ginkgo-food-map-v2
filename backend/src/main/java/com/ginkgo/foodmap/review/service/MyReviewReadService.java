package com.ginkgo.foodmap.review.service;

import com.ginkgo.foodmap.restaurant.service.RestaurantReviewTargetService;
import com.ginkgo.foodmap.review.dto.ReviewListQuery;
import com.ginkgo.foodmap.review.entity.ReviewPhotoReferenceEntity;
import com.ginkgo.foodmap.review.entity.ReviewReadEntity;
import com.ginkgo.foodmap.review.mapper.MyReviewReadMapper;
import com.ginkgo.foodmap.review.mapper.ReviewPhotoMapper;
import com.ginkgo.foodmap.review.policy.ReviewHistoryCursorPolicy;
import com.ginkgo.foodmap.review.vo.MyReviewPageVo;
import com.ginkgo.foodmap.user.dto.PublicAuthor;
import com.ginkgo.foodmap.user.service.PublicAuthorService;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/** 跨店作者点评读取；目标、可见性、计数和正文来自同一RR只读快照，不依赖搜索索引。 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class MyReviewReadService {
    private final MyReviewReadMapper mapper;
    private final RestaurantReviewTargetService restaurants;
    private final PublicAuthorService authors;
    private final ReviewPhotoMapper photos;

    /** 本人入口仅使用认证身份；公开主页服务先核验目标用户，外部请求不能直接调用本流程或指定offset。 */
    public MyReviewPageVo list(long userId, ReviewListQuery query) {
        if (userId <= 0) {
            throw new IllegalArgumentException("Authenticated user required");
        }
        var result = new MyReviewPageVo();
        var author = authors.find(List.of(userId)).get(Long.toString(userId));
        if (author == null) {
            author = new PublicAuthor();
            author.setId(Long.toString(userId));
            author.setUsername("用户不可用");
        }
        result.setAuthor(author);
        result.setPage(query.getPage());
        result.setPageSize(query.getPageSize());
        var targets = restaurants.visible(mapper.targets(userId));
        var windows = restaurants.visibleWindows(mapper.windowTargets(userId));
        if (targets.isEmpty() && windows.isEmpty()) {
            return result;
        }
        var ids = List.copyOf(targets.keySet());
        var windowIds = List.copyOf(windows.keySet());
        var counts = mapper.mixedCounts(userId, ids, windowIds);
        result.setTotalGroups(counts.getTotalGroups());
        result.setTotalReviews(counts.getTotalReviews());
        result.setTotalPages(
                counts.getTotalGroups() / query.getPageSize()
                        + (counts.getTotalGroups() % query.getPageSize() == 0 ? 0 : 1));
        if (query.getOffset() >= counts.getTotalGroups()) {
            return result;
        }
        var rows = mapper.mixedPage(userId, ids, windowIds, query.getPageSize(), query.getOffset());
        if (rows.isEmpty()) {
            return result;
        }
        var references =
                photos.references(rows.stream().map(ReviewReadEntity::getId).toList()).stream()
                        .collect(Collectors.groupingBy(ReviewPhotoReferenceEntity::getReviewId));
        result.setRecords(
                rows.stream()
                        .map(
                                row -> {
                                    var group = new MyReviewPageVo.Group();
                                    group.setRestaurant(
                                            row.getWindowId() == null
                                                    ? targets.get(row.getRestaurantId())
                                                    : windows.get(row.getWindowId()));
                                    group.setHistoryCount(row.getHistoryCount());
                                    if (row.getHistoryCount() > 0) {
                                        group.setNextHistoryCursor(
                                                ReviewHistoryCursorPolicy.encode(
                                                        row.getWindowId() == null
                                                                ? row.getRestaurantId()
                                                                : -row.getWindowId(),
                                                        userId,
                                                        row.getCreatedAt(),
                                                        row.getId()));
                                    }
                                    group.setCurrentReview(
                                            ReviewReadService.current(
                                                    row,
                                                    references.getOrDefault(
                                                            row.getId(), List.of())));
                                    return group;
                                })
                        .toList());
        return result;
    }
}
