package com.ginkgo.foodmap.review.mapper;

import com.ginkgo.foodmap.review.entity.ReplyEntity;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 回复回执属于review模块；不保存正文，硬删除后仍保留原作者与发布请求的唯一性。 */
@Mapper
public interface ReplyDeleteMapper {
    /** 与新回复同事务写入，唯一键阻止跨点评并发复用已删除请求号。 */
    @Insert(
            "INSERT INTO"
                    + " reply_request_receipts(author_user_id,client_request_id,reply_id,review_id)"
                    + " VALUES(#{authorUserId},UUID_TO_BIN(#{clientRequestId}),#{id},#{reviewId})")
    int record(ReplyEntity reply);

    /** 已删除的发布请求不能重建内容，失败重试必须沿用原请求号得到明确冲突。 */
    @Select(
            "SELECT COUNT(*) FROM reply_request_receipts WHERE author_user_id=#{author} AND"
                    + " client_request_id=UUID_TO_BIN(#{uuid}) AND deleted_at IS NOT NULL")
    int deletedRequest(@Param("author") long author, @Param("uuid") String uuid);

    /** 调用者已取得父店及点评锁；删除回执和正文必须同事务提交。 */
    @Update(
            "UPDATE reply_request_receipts SET deleted_at=CURRENT_TIMESTAMP(6) WHERE reply_id=#{id}"
                    + " AND deleted_at IS NULL")
    int tombstone(long id);

    /** 整条点评删除时也保留回复的防重放资格，不保留任何关联回复正文。 */
    @Update(
            "UPDATE reply_request_receipts SET deleted_at=CURRENT_TIMESTAMP(6) WHERE"
                    + " review_id=#{id} AND deleted_at IS NULL")
    int tombstoneReview(long id);

    /** 只有原作者可重试成功；未知编号与其他作者分别交给服务返回404和403。 */
    @Select(
            "SELECT author_user_id FROM reply_request_receipts WHERE reply_id=#{id} AND deleted_at"
                    + " IS NOT NULL")
    Long deletedOwner(long id);

    /** 防御性重复核验作者，回复没有子回复；点赞关系与原通知保留用于历史定位。 */
    @Delete("DELETE FROM review_replies WHERE id=#{id} AND author_user_id=#{author}")
    int remove(@Param("id") long id, @Param("author") long author);
}
