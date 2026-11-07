package com.ginkgo.foodmap.user.mapper;

import com.ginkgo.foodmap.user.vo.PublicProfileVo;
import org.apache.ibatis.annotations.*;

/** 仅选择公开资料，不读取邮箱、密码等私有列；停用不自动隐藏历史正常内容。 */
@Mapper
public interface PublicProfileMapper {
    @Select("SELECT CAST(id AS CHAR) user_id,username,bio FROM users WHERE id=#{id} AND status<>'DELETED' AND email_verified=TRUE")
    PublicProfileVo find(long id);
}
