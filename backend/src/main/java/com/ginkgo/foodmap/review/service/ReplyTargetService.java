package com.ginkgo.foodmap.review.service;

import com.ginkgo.foodmap.common.exception.BusinessException;
import com.ginkgo.foodmap.restaurant.service.RestaurantReadService;
import com.ginkgo.foodmap.review.exception.ReplyErrorCode;
import com.ginkgo.foodmap.review.mapper.*;
import com.ginkgo.foodmap.review.policy.ReplyPolicy;
import com.ginkgo.foodmap.review.vo.ReplyTargetVo;
import com.ginkgo.foodmap.security.service.CurrentActorService;
import com.ginkgo.foodmap.user.dto.PublicAuthor;
import com.ginkgo.foodmap.user.service.PublicAuthorService;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.util.List;

/** 独立于通知持久化的源内容鉴权读取；历史点评和任意位置的回复均可直接按ID定位。 */
@Service
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class ReplyTargetService {
    private final ReplyMapper replies;
    private final ReviewReadMapper reviews;
    private final ReviewPhotoMapper photos;
    private final RestaurantReadService restaurants;
    private final CurrentActorService actors;
    private final PublicAuthorService authors;

    /** 同一RR快照核验回复、点评作者、父店及原文；删除或越权统一404，不从通知恢复正文。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReplyTargetVo target(String id) {
        long owner = actors.requireActor().getUserId();
        var reply = replies.find(ReplyPolicy.reviewId(id));
        if (reply == null) {
            throw new BusinessException(ReplyErrorCode.NOT_FOUND);
        }
        Long restaurant = replies.restaurant(reply.getReviewId());
        if (restaurant == null) {
            throw new BusinessException(ReplyErrorCode.NOT_FOUND);
        }
        var review = reviews.anchor(restaurant, owner, reply.getReviewId());
        if (review == null) {
            throw new BusinessException(ReplyErrorCode.NOT_FOUND);
        }
        restaurants.requireVisible(restaurant);
        var names =
                authors.find(List.of(owner, reply.getAuthorUserId()).stream().distinct().toList());
        var result = new ReplyTargetVo();
        result.setUserId(Long.toString(owner));
        result.setRestaurantId(restaurant.toString());
        result.setReviewId(review.getId().toString());
        result.setReplyId(reply.getId().toString());
        result.setReviewAuthor(name(names, owner));
        result.setReplyAuthor(name(names, reply.getAuthorUserId()));
        var content = ReviewReadService.current(review, photos.references(List.of(review.getId())));
        var latest = reviews.latest(restaurant, owner);
        content.setScoringStatus(
                latest != null && latest.getId().equals(review.getId()) ? "current" : "history");
        result.setReview(content);
        result.setReplyBody(reply.getBody());
        result.setReplyCreatedAt(reply.getCreatedAt());
        return result;
    }

    private PublicAuthor name(java.util.Map<String, PublicAuthor> names, long id) {
        var value = names.get(Long.toString(id));
        if (value != null) {
            return value;
        }
        var missing = new PublicAuthor();
        missing.setId(Long.toString(id));
        missing.setUsername("用户不可用");
        return missing;
    }
}
