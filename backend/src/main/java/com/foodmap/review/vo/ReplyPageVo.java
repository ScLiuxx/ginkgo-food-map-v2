package com.foodmap.review.vo;

import com.foodmap.user.dto.PublicAuthor;
import java.time.Instant;
import java.util.List;
import lombok.Data;

/** 回复分页公开协议，ID用字符串避免前端整数精度损失。 */
@Data
public class ReplyPageVo {
    private List<Item> records = List.of();
    private boolean hasMore;
    private String nextCursor;

    /** 公开作者信息只包含账号ID和用户名。 */
    @Data
    public static class Item {
        private String id;
        private PublicAuthor author;
        private String body;
        private Instant createdAt;
    }
}
