package com.ginkgo.foodmap.file.mapper;

import com.ginkgo.foodmap.file.entity.MediaFile;
import org.apache.ibatis.annotations.*;
import java.util.List;

/** 仅访问file模块表；预登记待清理记录，让OSS成功而应用崩溃的上传仍可追踪。 */
@Mapper
public interface MediaFileMapper {
    /** 上传前提交清理凭据；一小时保护窗口远大于SDK的30秒请求上限。 */
    @Insert("""
        INSERT INTO media_files(uploader_user_id,bucket_name,storage_key,content_type,byte_size,width,height,sha256,status,expires_at,purpose)
        VALUES(#{uploaderUserId},#{bucketName},#{storageKey},'image/jpeg',#{byteSize},#{width},#{height},UNHEX(#{sha256}),
        'UPLOADING',TIMESTAMPADD(HOUR,1,CURRENT_TIMESTAMP(6)),#{purpose})
        """)
    @Options(useGeneratedKeys=true,keyProperty="id")
    int reserve(MediaFile file);

    /** OSS成功后才开放绑定；若保护窗口已过，拒绝激活，保留清理凭据。 */
    @Update("""
        UPDATE media_files SET status='TEMPORARY',expires_at=TIMESTAMPADD(SECOND,#{ttl},CURRENT_TIMESTAMP(6))
        WHERE id=#{id} AND status='UPLOADING' AND expires_at>CURRENT_TIMESTAMP(6)
        """)
    int activate(@Param("id") long id,@Param("ttl") long ttl);

    /** 响应仅返回安全元数据；统一使用数据库时间确定有效期。 */
    @Select("SELECT * FROM media_files WHERE id=#{id}")
    MediaFile find(long id);

    /** 绑定、取消与到期处理统一锁媒体行，仅在短事务内转换状态。 */
    @Select("SELECT * FROM media_files WHERE id=#{id} FOR UPDATE")
    MediaFile lock(long id);

    /** 不可逆删除意图；提交后才能执行OSS删除。 */
    @Update("UPDATE media_files SET expires_at=CASE WHEN status='UPLOADING' THEN TIMESTAMPADD(HOUR,1,CURRENT_TIMESTAMP(6)) ELSE CURRENT_TIMESTAMP(6) END,status='PENDING_DELETE' WHERE id=#{id} AND status IN ('UPLOADING','TEMPORARY')")
    int markPending(long id);

    /** 加入点评事务，使用数据库时间核验有效期。 */
    @Update("UPDATE media_files SET status='BOUND',expires_at=NULL,bound_at=CURRENT_TIMESTAMP(6) WHERE id=#{id} AND uploader_user_id=#{owner} AND status='TEMPORARY' AND expires_at>CURRENT_TIMESTAMP(6)")
    int bind(@Param("id") long id,@Param("owner") long owner);

    /** 点评删除事务提交可重试清理意图，云对象交给既有回收任务删除。 */
    @Update("UPDATE media_files SET status='PENDING_DELETE',expires_at=CURRENT_TIMESTAMP(6) WHERE id=#{id} AND uploader_user_id=#{owner} AND status='BOUND'")
    int releaseBound(@Param("id") long id,@Param("owner") long owner);

    /** 有界批次只扫描清理索引，BOUND文件永远不进入清理候选。 */
    @Select("""
        SELECT id FROM media_files WHERE status IN ('UPLOADING','TEMPORARY','PENDING_DELETE')
        AND expires_at<=CURRENT_TIMESTAMP(6) ORDER BY expires_at,id LIMIT 50
        """)
    List<Long> expired();

    /** 在独立短事务内锁行并重新核验期限，提交删除意图后才进行云端请求。 */
    @Select("""
        SELECT * FROM media_files WHERE id=#{id} AND status IN ('UPLOADING','TEMPORARY','PENDING_DELETE')
        AND expires_at<=CURRENT_TIMESTAMP(6) FOR UPDATE
        """)
    MediaFile lockExpired(long id);

    /** OSS已删除（包括原本不存在）后才移除登记；事务回滚会留下记录供重试。 */
    @Delete("DELETE FROM media_files WHERE id=#{id} AND status='PENDING_DELETE' AND expires_at<=CURRENT_TIMESTAMP(6)")
    int delete(long id);
}
