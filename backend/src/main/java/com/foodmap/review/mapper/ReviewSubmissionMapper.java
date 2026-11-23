package com.foodmap.review.mapper;

import com.foodmap.review.entity.ReviewSubmissionEntity;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** 仅访问review模块的待发布表；读取按作者过滤，写操作要求调用方先锁定并核对新店申请。 */
@Mapper
public interface ReviewSubmissionMapper {
    /** 作者条件必须来自认证上下文；只读查询不加行锁，复用调用方的详情快照。 */
    @Select(
            """
            SELECT id,status,taste_score,value_score,service_score,ambience_score,
                   recommendation,body,published_review_id
            FROM review_submissions
            WHERE restaurant_request_id=#{requestId} AND author_user_id=#{owner}
            """)
    ReviewSubmissionEntity findOwn(@Param("requestId") long requestId, @Param("owner") long owner);

    /** 已发布回执不再提供私有图片；即使残留旧关联，也不能恢复公开点评之外的访问路径。 */
    @Select(
            """
            SELECT p.position FROM review_submission_photos p
            JOIN review_submissions s ON s.id=p.submission_id
            WHERE s.restaurant_request_id=#{requestId} AND s.author_user_id=#{owner}
              AND s.status IN ('WAITING','WITHDRAWN') ORDER BY p.position
            """)
    List<Integer> ownPhotoPositions(@Param("requestId") long requestId, @Param("owner") long owner);

    /** 按申请、作者和展示位置定位图片，禁止通过猜测文件ID绕过附带点评的访问范围。 */
    @Select(
            """
            SELECT p.media_file_id FROM review_submission_photos p
            JOIN review_submissions s ON s.id=p.submission_id
            WHERE s.restaurant_request_id=#{requestId} AND s.author_user_id=#{owner}
              AND s.status IN ('WAITING','WITHDRAWN') AND p.position=#{position}
            """)
    Long findOwnPhoto(
            @Param("requestId") long requestId,
            @Param("owner") long owner,
            @Param("position") int position);

    /** 调用方已持有待发布记录锁，按原展示顺序转入正式点评，不跨模块访问文件表。 */
    @Select(
            "SELECT media_file_id FROM review_submission_photos WHERE submission_id=#{submission}"
                    + " ORDER BY position")
    List<Long> photoIds(long submission);

    /** 当前读避免旧RR快照漏掉刚刚提交的点评；同申请的提交与后续操作统一按申请→点评加锁。 */
    @Select(
            """
            SELECT id,restaurant_request_id,origin_request_id,author_user_id,status,
                   taste_score,value_score,service_score,ambience_score,recommendation,body,
                   HEX(request_hash) request_hash,HEX(content_hash) content_hash,
                   BIN_TO_UUID(client_request_id) client_request_id,
                   BIN_TO_UUID(publish_request_id) publish_request_id,published_review_id
            FROM review_submissions WHERE restaurant_request_id=#{requestId} FOR UPDATE
            """)
    ReviewSubmissionEntity lockByRequest(long requestId);

    /** 原申请重试时，点评可能已迁移到后续申请；通过原作者/UUID取回同一回执，不能再创建副本。 */
    @Select(
            """
            SELECT id,restaurant_request_id,origin_request_id,author_user_id,status,
                   HEX(request_hash) request_hash,BIN_TO_UUID(client_request_id) client_request_id
            FROM review_submissions
            WHERE author_user_id=#{owner} AND client_request_id=UUID_TO_BIN(#{uuid}) FOR UPDATE
            """)
    ReviewSubmissionEntity lockByClient(@Param("owner") long owner, @Param("uuid") String uuid);

    /** 本人待审修改只更新当前内容摘要，保留首次请求摘要、原申请和预留发布UUID。 */
    @Update(
            """
            UPDATE review_submissions SET taste_score=#{tasteScore},value_score=#{valueScore},
                service_score=#{serviceScore},ambience_score=#{ambienceScore},
                recommendation=#{recommendation},body=#{body},content_hash=UNHEX(#{contentHash})
            WHERE id=#{id} AND status='WAITING' AND author_user_id=#{authorUserId}
            """)
    int updateContent(ReviewSubmissionEntity row);

    /** 撤回保留本人内容及图片，不误用发布清空规则；时间回拨时仍满足表约束。 */
    @Update(
            """
UPDATE review_submissions SET status='WITHDRAWN',withdrawn_at=GREATEST(CURRENT_TIMESTAMP(6),created_at)
WHERE id=#{id} AND author_user_id=#{owner} AND status='WAITING'
""")
    int withdraw(@Param("id") long id, @Param("owner") long owner);

    /** 重提移动同一条待发布点评；首次来源、首次摘要与发布UUID不变，一条点评只绑定当前申请。 */
    @Update(
            """
UPDATE review_submissions SET restaurant_request_id=#{next}
WHERE id=#{id} AND restaurant_request_id=#{previous} AND author_user_id=#{owner} AND status='WAITING'
""")
    int move(
            @Param("id") long id,
            @Param("previous") long previous,
            @Param("next") long next,
            @Param("owner") long owner);

    /** 发布关联转移与正式点评共用外层事务；先删除旧关联，再写仅含结果的发布回执。 */
    @Delete("DELETE FROM review_submission_photos WHERE submission_id=#{submission}")
    int deletePhotos(long submission);

    /** 已持有待发布行锁；一次性清空正文、评分及态度，避免正式点评删除后仍保留私有副本。 */
    @Update(
            """
            UPDATE review_submissions
            SET status='PUBLISHED',taste_score=NULL,value_score=NULL,service_score=NULL,
                ambience_score=NULL,recommendation=NULL,body=NULL,published_review_id=#{review},
                published_at=CURRENT_TIMESTAMP(6)
            WHERE id=#{submission} AND status='WAITING'
            """)
    int markPublished(@Param("submission") long submission, @Param("review") long review);

    /** 外层申请唯一键已串行化同一次提交；独立发布UUID首次生成，重放不再插入或重新绑定文件。 */
    @Insert(
            """
INSERT INTO review_submissions(restaurant_request_id,origin_request_id,author_user_id,
    taste_score,value_score,service_score,ambience_score,recommendation,body,
    client_request_id,request_hash,content_hash,publish_request_id)
VALUES(#{restaurantRequestId},#{originRequestId},#{authorUserId},#{tasteScore},#{valueScore},
    #{serviceScore},#{ambienceScore},#{recommendation},#{body},UUID_TO_BIN(#{clientRequestId}),
    UNHEX(#{requestHash}),UNHEX(#{contentHash}),UUID_TO_BIN(#{publishRequestId}))
""")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ReviewSubmissionEntity row);

    /** 展示位置遵循客户端原始顺序；文件锁顺序由file服务统一升序处理。 */
    @Insert(
            """
            INSERT INTO review_submission_photos(submission_id,position,media_file_id)
            VALUES(#{submission},#{position},#{file})
            """)
    int insertPhoto(
            @Param("submission") long submission,
            @Param("position") int position,
            @Param("file") long file);
}
