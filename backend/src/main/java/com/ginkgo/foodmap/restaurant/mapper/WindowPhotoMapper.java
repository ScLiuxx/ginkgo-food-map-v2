package com.ginkgo.foodmap.restaurant.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** 仅访问restaurant模块窗口相册；写入由父食堂→窗口主行锁串行化。 */
@Mapper
public interface WindowPhotoMapper {
    /** 以入册时间稳定排序，首张为封面，文件ID只用于关联及同刻排序。 */
    @Select(
            "SELECT media_file_id FROM canteen_window_photos WHERE window_id=#{id} ORDER BY"
                + " created_at,media_file_id")
    List<Long> list(long id);

    /** 文件绑定与关系插入共用事务，唯一索引阻止同一文件进入其他窗口。 */
    @Insert("INSERT INTO canteen_window_photos(window_id,media_file_id) VALUES(#{window},#{file})")
    int insert(@Param("window") long window, @Param("file") long file);

    /** 删除仅针对已经核对的本窗口关联。 */
    @Delete("DELETE FROM canteen_window_photos WHERE window_id=#{window} AND media_file_id=#{file}")
    int delete(@Param("window") long window, @Param("file") long file);

    /** 新增/删除后同步封面，删除首张自动使用下一张；不会改变窗口评分。 */
    @Update(
            "UPDATE canteen_windows SET cover_file_id=#{file},updated_by=#{actor} WHERE"
                + " id=#{window}")
    int cover(@Param("window") long window, @Param("file") Long file, @Param("actor") long actor);
}
