package com.ginkgo.foodmap.review.service;

import com.ginkgo.foodmap.common.exception.BusinessException;
import com.ginkgo.foodmap.review.exception.ReviewErrorCode;
import com.ginkgo.foodmap.review.mapper.ReviewPhotoMapper;
import com.ginkgo.foodmap.review.mapper.ReviewReadMapper;
import com.ginkgo.foodmap.review.policy.ReviewReadPolicy;
import com.ginkgo.foodmap.review.vo.ReviewDetailVo;
import com.ginkgo.foodmap.user.dto.PublicAuthor;
import com.ginkgo.foodmap.user.service.PublicAuthorService;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 精确读取正式点评，供消息定位使用；不依赖作者组所在页，也不以最新代表替换历史ID。 */
@Service
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class ReviewDetailService {
    private final ReviewParentService parents;
    private final ReviewReadMapper reviews;
    private final ReviewPhotoMapper photos;
    private final PublicAuthorService authors;

    /** 同一只读快照核验点评、父店／窗口、最新代表及图片引用；不访问OSS或修改已读状态。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReviewDetailVo detail(String value) {
        long id = ReviewReadPolicy.restaurantId(value);
        var source = parents.visible(id);
        if (source == null) {
            throw new BusinessException(ReviewErrorCode.NOT_FOUND);
        }
        long owner = source.getAuthorUserId();
        var row =
                source.getWindowId() == null
                        ? reviews.anchor(source.getRestaurantId(), owner, id)
                        : reviews.windowAnchor(source.getWindowId(), owner, id);
        if (row == null) {
            throw new BusinessException(ReviewErrorCode.NOT_FOUND);
        }
        var latest =
                source.getWindowId() == null
                        ? reviews.latest(source.getRestaurantId(), owner)
                        : reviews.windowLatest(source.getWindowId(), owner);
        var content = ReviewReadService.current(row, photos.references(List.of(id)));
        content.setScoringStatus(
                latest != null && latest.getId().equals(row.getId()) ? "current" : "history");
        var author = authors.find(List.of(owner)).get(Long.toString(owner));
        if (author == null) {
            author = new PublicAuthor();
            author.setId(Long.toString(owner));
            author.setUsername("用户不可用");
        }
        var result = new ReviewDetailVo();
        result.setRestaurantId(
                source.getRestaurantId() == null ? null : source.getRestaurantId().toString());
        result.setWindowId(source.getWindowId() == null ? null : source.getWindowId().toString());
        result.setAuthor(author);
        result.setReview(content);
        return result;
    }
}
