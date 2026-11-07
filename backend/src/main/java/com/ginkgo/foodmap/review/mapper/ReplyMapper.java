package com.ginkgo.foodmap.review.mapper;

import com.ginkgo.foodmap.review.entity.ReplyEntity;

import org.apache.ibatis.annotations.*;

import java.util.List;

/** 只访问review自有表；所有值使用绑定参数。 */
@Mapper
public interface ReplyMapper {
    String COLUMNS =
            "id,review_id,author_user_id,body,BIN_TO_UUID(client_request_id) AS client_request_id,HEX(request_hash) AS request_hash,created_at";

    /** 先定位父店，随后经restaurant公开服务锁定父店。 */
    @Select("SELECT restaurant_id FROM reviews WHERE id=#{id}")
    Long restaurant(long id);

    /** 父店锁取得后再锁点评，未来删除也须遵守父店到点评的锁顺序。 */
    @Select("SELECT restaurant_id FROM reviews WHERE id=#{id} FOR UPDATE")
    Long lockReview(long id);

    /** 调用方已锁定点评，随后读取真实作者，不接受客户端指定通知收件人。 */
    @Select("SELECT author_user_id FROM reviews WHERE id=#{id}")
    Long reviewAuthor(long id);

    /** 定位一条回复，不依赖列表页或游标；仍需服务核验父点评和店铺可见性。 */
    @Select("SELECT " + COLUMNS + " FROM review_replies WHERE id=#{id}")
    ReplyEntity find(long id);

    /** 唯一键覆盖同账号跨点评的请求编号。 */
    @Select(
            "SELECT "
                    + COLUMNS
                    + " FROM review_replies WHERE author_user_id=#{author} AND client_request_id=UUID_TO_BIN(#{uuid})")
    ReplyEntity request(@Param("author") long author, @Param("uuid") String uuid);

    /** 锚点查询限制所属点评，阻止跨点评游标。 */
    @Select("SELECT " + COLUMNS + " FROM review_replies WHERE review_id=#{review} AND id=#{id}")
    ReplyEntity anchor(@Param("review") long review, @Param("id") long id);

    /** 时间不倒退，使已打开的升序游标能继续读取后续发布的回复。 */
    @Select(
            "SELECT "
                    + COLUMNS
                    + " FROM review_replies WHERE review_id=#{review} ORDER BY created_at DESC,id DESC LIMIT 1")
    ReplyEntity latest(long review);

    /** 由数据库生成ID，时间下界来自同一父锁内的最新回复。 */
    @Insert(
            """
        INSERT INTO review_replies(review_id,author_user_id,body,client_request_id,request_hash,created_at)
        VALUES(#{reviewId},#{authorUserId},#{body},UUID_TO_BIN(#{clientRequestId}),UNHEX(#{requestHash}),
        GREATEST(CURRENT_TIMESTAMP(6),COALESCE(#{createdAt,jdbcType=TIMESTAMP},CURRENT_TIMESTAMP(6))))
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ReplyEntity row);

    /** 查询多取一条判断hasMore；同时间用ID排他比较，避免OFFSET漂移。 */
    @Select(
            """
        <script>
        SELECT id,review_id,author_user_id,body,created_at FROM review_replies WHERE review_id=#{review}
        <if test="boundary != null">AND (created_at &gt; #{boundary.createdAt} OR (created_at=#{boundary.createdAt} AND id &gt; #{boundary.id}))</if>
        ORDER BY created_at ASC,id ASC LIMIT #{limit}
        </script>
        """)
    List<ReplyEntity> page(
            @Param("review") long review,
            @Param("boundary") ReplyEntity boundary,
            @Param("limit") int limit);
}
