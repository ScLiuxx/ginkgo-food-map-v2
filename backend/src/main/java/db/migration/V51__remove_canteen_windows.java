package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.SQLException;

/** 永久删除窗口体系；先持久保存清理范围，再清数据，最后拆结构，允许修复后继续中断的DDL。 */
public class V51__remove_canteen_windows extends BaseJavaMigration {
    @Override
    public boolean canExecuteInTransaction() {
        return false;
    }

    @Override
    public void migrate(Context context) throws Exception {
        Connection db = context.getConnection();
        execute(
                db,
                "CREATE TABLE IF NOT EXISTS _v51_window_reviews (id BIGINT UNSIGNED PRIMARY KEY)");
        execute(
                db,
                "CREATE TABLE IF NOT EXISTS _v51_window_replies (id BIGINT UNSIGNED PRIMARY KEY)");
        execute(
                db,
                "CREATE TABLE IF NOT EXISTS _v51_window_files (id BIGINT UNSIGNED PRIMARY KEY)");
        execute(
                db,
                "CREATE TABLE IF NOT EXISTS _v51_window_users (id BIGINT UNSIGNED PRIMARY KEY)");
        if (column(db, "reviews", "window_id")) {
            execute(
                    db,
                    "INSERT IGNORE INTO _v51_window_reviews SELECT id FROM reviews WHERE window_id"
                            + " IS NOT NULL");
        }
        if (column(db, "review_request_receipts", "window_id")) {
            execute(
                    db,
                    "INSERT IGNORE INTO _v51_window_reviews SELECT review_id FROM"
                            + " review_request_receipts WHERE window_id IS NOT NULL");
        }
        execute(
                db,
                "INSERT IGNORE INTO _v51_window_reviews SELECT source_id FROM growth_rewards WHERE"
                        + " reward_type='FIRST_STORE_REVIEW' AND business_key LIKE 'WINDOW:%'");
        execute(
                db,
                "INSERT IGNORE INTO _v51_window_replies SELECT p.id FROM review_replies p JOIN"
                        + " _v51_window_reviews x ON x.id=p.review_id");
        execute(
                db,
                "INSERT IGNORE INTO _v51_window_replies SELECT p.reply_id FROM"
                    + " reply_request_receipts p JOIN _v51_window_reviews x ON x.id=p.review_id");
        execute(
                db,
                "INSERT IGNORE INTO _v51_window_files SELECT p.media_file_id FROM review_photos p"
                        + " JOIN _v51_window_reviews x ON x.id=p.review_id");
        if (table(db, "canteen_window_photos")) {
            execute(
                    db,
                    "INSERT IGNORE INTO _v51_window_files SELECT media_file_id FROM"
                            + " canteen_window_photos");
        }
        if (table(db, "canteen_windows")) {
            execute(
                    db,
                    "INSERT IGNORE INTO _v51_window_files SELECT cover_file_id FROM canteen_windows"
                            + " WHERE cover_file_id IS NOT NULL");
        }
        execute(
                db,
                "INSERT IGNORE INTO _v51_window_files SELECT id FROM media_files WHERE"
                        + " purpose='WINDOW'");
        execute(
                db,
                "INSERT IGNORE INTO _v51_window_users SELECT g.user_id FROM growth_rewards g LEFT"
                    + " JOIN _v51_window_reviews x ON x.id=g.source_id WHERE"
                    + " (g.source_type='REVIEW' AND x.id IS NOT NULL) OR"
                    + " (g.reward_type='FIRST_STORE_REVIEW' AND g.business_key LIKE 'WINDOW:%')");

        // 发布时必须停止旧应用，防止迁移清理范围产生后又创建窗口或关联内容。
        boolean autoCommit = db.getAutoCommit();
        db.setAutoCommit(false);
        try {
            // 待清理记录改为通用点评媒体用途，仅供清理器按原bucket/key删除，绝不重新绑定。
            // 在途上传及既有保护期都不得提前结束，避免迟到上传重新留下孤儿对象。
            execute(
                    db,
                    """
                    UPDATE media_files f JOIN _v51_window_files x ON x.id=f.id
                    SET f.expires_at=CASE
                        WHEN f.status='UPLOADING' THEN GREATEST(COALESCE(f.expires_at,CURRENT_TIMESTAMP(6)),DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 1 HOUR))
                        WHEN f.status='PENDING_DELETE' THEN GREATEST(COALESCE(f.expires_at,CURRENT_TIMESTAMP(6)),CURRENT_TIMESTAMP(6))
                        ELSE CURRENT_TIMESTAMP(6) END,
                        f.status='PENDING_DELETE',f.purpose=IF(f.purpose='WINDOW','REVIEW',f.purpose)
                    """);
            execute(
                    db,
                    "DELETE n FROM notification_messages n LEFT JOIN _v51_window_reviews x ON"
                            + " x.id=n.review_id LEFT JOIN content_reports p ON"
                            + " n.notification_type='REPORT_RESULT' AND n.source_id=p.id LEFT JOIN"
                            + " _v51_window_reviews y ON y.id=p.review_id WHERE x.id IS NOT NULL OR"
                            + " y.id IS NOT NULL");
            execute(
                    db,
                    "DELETE p FROM content_reports p JOIN _v51_window_reviews x ON"
                            + " x.id=p.review_id");
            execute(
                    db,
                    "DELETE l FROM content_likes l LEFT JOIN _v51_window_reviews x ON"
                            + " l.target_type='REVIEW' AND x.id=l.target_id LEFT JOIN"
                            + " _v51_window_replies y ON l.target_type='REPLY' AND y.id=l.target_id"
                            + " WHERE x.id IS NOT NULL OR y.id IS NOT NULL");
            execute(
                    db,
                    "DELETE g FROM growth_rewards g LEFT JOIN _v51_window_reviews x ON"
                        + " x.id=g.source_id WHERE (g.source_type='REVIEW' AND x.id IS NOT NULL) OR"
                        + " (g.reward_type='FIRST_STORE_REVIEW' AND g.business_key LIKE"
                        + " 'WINDOW:%')");
            execute(
                    db,
                    "UPDATE growth_accounts a JOIN _v51_window_users x ON x.id=a.user_id LEFT JOIN"
                            + " (SELECT user_id,SUM(amount) amount FROM growth_rewards WHERE"
                            + " status='GRANTED' GROUP BY user_id) g ON g.user_id=a.user_id SET"
                            + " a.experience=COALESCE(g.amount,0)");
            execute(
                    db,
                    "DELETE p FROM reply_request_receipts p JOIN _v51_window_reviews x ON"
                            + " x.id=p.review_id");
            execute(
                    db,
                    "DELETE p FROM review_replies p JOIN _v51_window_reviews x ON"
                            + " x.id=p.review_id");
            execute(
                    db,
                    "DELETE p FROM review_photos p JOIN _v51_window_reviews x ON x.id=p.review_id");
            execute(
                    db,
                    "DELETE p FROM review_request_receipts p JOIN _v51_window_reviews x ON"
                            + " x.id=p.review_id");
            execute(db, "DELETE p FROM reviews p JOIN _v51_window_reviews x ON x.id=p.id");
            // 食堂历史直接点评保留，只按每位作者最新一条重建，普通餐厅统计不改动。
            execute(
                    db,
                    "INSERT IGNORE INTO restaurant_review_statistics(restaurant_id) SELECT id FROM"
                            + " restaurants WHERE restaurant_type='CANTEEN'");
            execute(
                    db,
                    """
                    UPDATE restaurant_review_statistics s JOIN restaurants r ON r.id=s.restaurant_id
                    LEFT JOIN (SELECT restaurant_id,COUNT(*) reviews,COUNT(DISTINCT author_user_id) participants
                               FROM reviews GROUP BY restaurant_id) c ON c.restaurant_id=r.id
                    LEFT JOIN (SELECT restaurant_id,SUM(recommendation='RECOMMENDED') recommended,
                        SUM(taste_score) taste,SUM(value_score) value_score,SUM(service_score) service,
                        SUM(ambience_score) ambience,SUM(overall_score) overall
                        FROM (SELECT v.*,ROW_NUMBER() OVER(PARTITION BY restaurant_id,author_user_id
                            ORDER BY created_at DESC,id DESC) representative FROM reviews v) ranked
                        WHERE representative=1 GROUP BY restaurant_id) a ON a.restaurant_id=r.id
                    SET s.review_count=COALESCE(c.reviews,0),s.participant_count=COALESCE(c.participants,0),
                        s.recommend_count=COALESCE(a.recommended,0),s.taste_sum=COALESCE(a.taste,0),
                        s.value_sum=COALESCE(a.value_score,0),s.service_sum=COALESCE(a.service,0),
                        s.ambience_sum=COALESCE(a.ambience,0),s.overall_score_sum=COALESCE(a.overall,0)
                    WHERE r.restaurant_type='CANTEEN'
                    """);
            execute(
                    db,
                    "UPDATE restaurants SET"
                        + " search_projection_version=search_projection_version+1,updated_at=updated_at"
                        + " WHERE restaurant_type='CANTEEN'");
            execute(
                    db,
                    "INSERT INTO restaurant_search_outbox(restaurant_id,projection_version) SELECT"
                            + " id,search_projection_version FROM restaurants WHERE"
                            + " restaurant_type='CANTEEN'");
            db.commit();
        } catch (Exception failure) {
            db.rollback();
            throw failure;
        } finally {
            db.setAutoCommit(autoCommit);
        }
        execute(db, "DROP TABLE IF EXISTS canteen_window_photos");
        execute(db, "DROP TABLE IF EXISTS window_review_statistics");
        execute(db, "DROP TABLE IF EXISTS canteen_windows");
        if (column(db, "reviews", "window_id")) {
            execute(
                    db,
                    """
                    ALTER TABLE reviews DROP CHECK chk_reviews_target,
                        DROP INDEX idx_reviews_window_time,DROP INDEX idx_reviews_window_author_time,
                        DROP COLUMN window_id,
                        MODIFY COLUMN restaurant_id BIGINT UNSIGNED NOT NULL COMMENT '餐厅或食堂逻辑ID',
                        ADD CONSTRAINT chk_reviews_target CHECK (restaurant_id BETWEEN 1 AND 9223372036854775807)
                    """);
        }
        if (column(db, "review_request_receipts", "window_id")) {
            execute(
                    db,
                    """
                    ALTER TABLE review_request_receipts DROP CHECK chk_review_receipts_target,
                        DROP COLUMN window_id,
                        MODIFY COLUMN restaurant_id BIGINT UNSIGNED NOT NULL COMMENT '餐厅或食堂逻辑ID',
                        ADD CONSTRAINT chk_review_receipts_target CHECK (restaurant_id BETWEEN 1 AND 9223372036854775807)
                    """);
        }
        if (index(db, "restaurants", "uk_restaurants_id_type")) {
            execute(db, "ALTER TABLE restaurants DROP INDEX uk_restaurants_id_type");
        }
        execute(
                db,
                "ALTER TABLE media_files DROP CHECK chk_media_files_purpose, ADD CONSTRAINT"
                    + " chk_media_files_purpose CHECK (purpose IN ('REVIEW','RESTAURANT','AVATAR')"
                    + " AND NOT REGEXP_LIKE(purpose,'[[:space:]]'))");
        for (String name : new String[] {"reviews", "replies", "files", "users"}) {
            execute(db, "DROP TABLE IF EXISTS _v51_window_" + name);
        }
    }

    private static void execute(Connection db, String sql) throws SQLException {
        try (var statement = db.createStatement()) {
            statement.execute(sql);
        }
    }

    private static boolean table(Connection db, String name) throws SQLException {
        return exists(
                db,
                "SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND"
                        + " table_name=?",
                name);
    }

    private static boolean column(Connection db, String table, String name) throws SQLException {
        return exists(
                db,
                "SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND"
                        + " table_name=? AND column_name=?",
                table,
                name);
    }

    private static boolean index(Connection db, String table, String name) throws SQLException {
        return exists(
                db,
                "SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND"
                        + " table_name=? AND index_name=?",
                table,
                name);
    }

    private static boolean exists(Connection db, String sql, String... parameters)
            throws SQLException {
        try (var statement = db.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setString(i + 1, parameters[i]);
            }
            try (var rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }
}
