package com.ginkgo.foodmap.review.mapper;

import com.ginkgo.foodmap.review.entity.ReviewEntity;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 仅操作review自有表；父店锁统一串行化发布、回复及删除，禁止在锁内调用OSS。 */
@Mapper
public interface ReviewDeleteMapper {
    /** 首次读取仅确定父店，取得父锁之后必须重读，避免检查与使用之间发生删除。 */
    @Select("SELECT " + ReviewWriteMapper.COLUMNS + " FROM reviews WHERE id=#{id}")
    ReviewEntity find(long id);

    /** 父资源取锁后重读点评，互动操作不能只依赖锁前事实。 */
    @Select("SELECT " + ReviewWriteMapper.COLUMNS + " FROM reviews WHERE id=#{id} FOR UPDATE")
    ReviewEntity lock(long id);

    /** 已删除请求占用原UUID，不允许重新发布；唯一键同时封住跨店并发重放窗口。 */
    @Select(
            "SELECT COUNT(*) FROM review_request_receipts WHERE author_user_id=#{author} AND"
                    + " client_request_id=UUID_TO_BIN(#{uuid}) AND deleted_at IS NOT NULL")
    int deletedRequest(@Param("author") long author, @Param("uuid") String uuid);

    /** 与点评插入同事务，任何冲突都回滚整次发布。 */
    @Insert(
            "INSERT INTO"
                + " review_request_receipts(author_user_id,client_request_id,review_id,restaurant_id,window_id)"
                + " VALUES(#{authorUserId},UUID_TO_BIN(#{clientRequestId}),#{id},#{restaurantId,jdbcType=BIGINT},#{windowId,jdbcType=BIGINT})")
    int record(ReviewEntity row);

    /** 回执只保留删除授权与防重放所需逻辑ID，不保留内容。 */
    @Update(
            "UPDATE review_request_receipts SET deleted_at=CURRENT_TIMESTAMP(6) WHERE"
                    + " review_id=#{id} AND deleted_at IS NULL")
    int tombstone(long id);

    /** 删除重试只对原作者成功，别人不能利用删除回执取得管理权限。 */
    @Select(
            "SELECT author_user_id FROM review_request_receipts WHERE review_id=#{id} AND"
                    + " deleted_at IS NOT NULL")
    Long deletedOwner(long id);

    /** 子记录有RESTRICT外键，必须在点评前显式删除，不转移回复到历史代表。 */
    @Delete("DELETE FROM review_replies WHERE review_id=#{id}")
    int replies(long id);

    @Delete("DELETE FROM review_photos WHERE review_id=#{id}")
    int photos(long id);

    @Delete("DELETE FROM reviews WHERE id=#{id}")
    int review(long id);
}
