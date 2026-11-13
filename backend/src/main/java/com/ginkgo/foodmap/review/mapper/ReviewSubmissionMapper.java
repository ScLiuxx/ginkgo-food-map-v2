package com.ginkgo.foodmap.review.mapper;

import com.ginkgo.foodmap.review.entity.ReviewSubmissionEntity;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 仅访问review模块的待发布表；调用方必须先锁定并核对新店申请。 */
@Mapper
public interface ReviewSubmissionMapper {
    /** 当前读避免旧RR快照漏掉刚刚提交的点评；同申请的提交与后续操作统一按申请→点评加锁。 */
    @Select(
            """
            SELECT id,restaurant_request_id,origin_request_id,author_user_id,status,
                   HEX(request_hash) request_hash,BIN_TO_UUID(client_request_id) client_request_id
            FROM review_submissions WHERE restaurant_request_id=#{requestId} FOR UPDATE
            """)
    ReviewSubmissionEntity lockByRequest(long requestId);

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
