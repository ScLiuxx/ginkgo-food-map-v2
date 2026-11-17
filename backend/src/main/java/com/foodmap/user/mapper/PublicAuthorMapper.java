package com.foodmap.user.mapper;

import com.foodmap.user.dto.PublicAuthor;
import org.apache.ibatis.annotations.*;
import java.util.List;

/** 按公开字段批量读取，不加载完整用户实体和密码等无关数据。 */
@Mapper
public interface PublicAuthorMapper {
    /** ID来自本页点评，使用参数绑定，账号停用不抹掉已经发布内容的署名。 */
    @Select("""
        <script>
        SELECT CAST(id AS CHAR) AS id,username FROM users WHERE id IN
        <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
        </script>
        """)
    List<PublicAuthor> find(@Param("ids") List<Long> ids);
}
