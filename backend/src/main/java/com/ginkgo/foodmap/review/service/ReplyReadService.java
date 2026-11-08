package com.ginkgo.foodmap.review.service;

import com.ginkgo.foodmap.common.exception.BusinessException;
import com.ginkgo.foodmap.review.dto.ReplyListQuery;
import com.ginkgo.foodmap.review.entity.ReplyEntity;
import com.ginkgo.foodmap.review.exception.ReplyErrorCode;
import com.ginkgo.foodmap.review.mapper.ReplyMapper;
import com.ginkgo.foodmap.review.policy.ReplyPolicy;
import com.ginkgo.foodmap.review.vo.ReplyPageVo;
import com.ginkgo.foodmap.user.dto.PublicAuthor;
import com.ginkgo.foodmap.user.service.PublicAuthorService;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 单段只读一致快照覆盖点评、父店、锚点、回复及批量署名，避免N+1。 */
@Service
@RequiredArgsConstructor
public class ReplyReadService {
    private final ReviewParentService parents;
    private final ReplyMapper mapper;
    private final PublicAuthorService authors;

    /** 升序按需读取；跨段不保持事务，删除锚点后要求重新加载。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReplyPageVo list(String review, ReplyListQuery query) {
        long id = ReplyPolicy.reviewId(review);
        if (parents.visible(id) == null) {
            throw new BusinessException(ReplyErrorCode.NOT_FOUND);
        }
        ReplyEntity boundary = null;
        if (query.getCursor() != null) {
            boundary = ReplyPolicy.decode(query.getCursor(), id);
            var anchor = mapper.anchor(id, boundary.getId());
            if (anchor == null || !anchor.getCreatedAt().equals(boundary.getCreatedAt())) {
                throw new BusinessException(ReplyErrorCode.INVALID_CURSOR);
            }
        }
        var rows = mapper.page(id, boundary, query.getPageSize() + 1);
        var result = new ReplyPageVo();
        result.setHasMore(rows.size() > query.getPageSize());
        rows = rows.subList(0, Math.min(rows.size(), query.getPageSize()));
        if (rows.isEmpty()) {
            return result;
        }
        var names =
                authors.find(rows.stream().map(ReplyEntity::getAuthorUserId).distinct().toList());
        result.setRecords(
                rows.stream()
                        .map(
                                row -> {
                                    var name = names.get(Long.toString(row.getAuthorUserId()));
                                    if (name == null) {
                                        name = new PublicAuthor();
                                        name.setId(Long.toString(row.getAuthorUserId()));
                                        name.setUsername("用户不可用");
                                    }
                                    var item = new ReplyPageVo.Item();
                                    item.setId(row.getId().toString());
                                    item.setAuthor(name);
                                    item.setBody(row.getBody());
                                    item.setCreatedAt(row.getCreatedAt());
                                    return item;
                                })
                        .toList());
        if (result.isHasMore()) {
            result.setNextCursor(ReplyPolicy.encode(rows.getLast()));
        }
        return result;
    }
}
