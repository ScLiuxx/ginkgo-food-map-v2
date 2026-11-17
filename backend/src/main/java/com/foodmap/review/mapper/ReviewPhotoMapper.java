package com.foodmap.review.mapper;

import org.apache.ibatis.annotations.*;
import java.util.List;

/** 只访问review拥有的点评与照片关联，不查询media_files或restaurants。 */
@Mapper
public interface ReviewPhotoMapper {
    /** 本页最多50条点评，一次取全部有序照片引用；不访问file模块或OSS。 */
    @Select("""
        <script>
        SELECT review_id,media_file_id,position FROM review_photos WHERE review_id IN
        <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
        ORDER BY review_id,position
        </script>
        """)
    List<com.foodmap.review.entity.ReviewPhotoReferenceEntity> references(@Param("ids") List<Long> ids);

    /** 原始提交顺序持久化，文件唯一约束防止同图重复用于其他点评。 */
    @Insert("INSERT INTO review_photos(review_id,position,media_file_id) VALUES(#{review},#{position},#{file})")
    int insert(@Param("review") long review,@Param("position") int position,@Param("file") long file);
    /** 幂等重试比较完整有序关联，不能重新要求已绑定图片仍为临时状态。 */
    @Select("SELECT media_file_id FROM review_photos WHERE review_id=#{review} ORDER BY position")
    List<Long> ids(long review);
    /** 资源访问先确定父店，公开可见性由餐厅公开服务负责。 */
    @Select("SELECT restaurant_id FROM reviews WHERE id=#{review}")
    Long restaurant(long review);
}
