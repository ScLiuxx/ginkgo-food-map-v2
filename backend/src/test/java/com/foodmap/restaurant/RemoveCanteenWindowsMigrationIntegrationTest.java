package com.foodmap.restaurant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.DriverManager;
import java.util.UUID;

/** 仅操作本轮创建的回环随机库，覆盖真实V50升级、旧删除回执、媒体清理及普通数据保留。 */
@EnabledIfSystemProperty(named = "review.integration", matches = "true")
class RemoveCanteenWindowsMigrationIntegrationTest {
    @Test
    void purgesWindowGraphPreservesDirectReviewsAndCanResumeAfterDdl() throws Exception {
        String database = "foodmap_retire_it_" + UUID.randomUUID().toString().replace("-", "");
        String server = System.getenv("REVIEW_TEST_DB_SERVER");
        if (server == null
                || !server.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]{1,5}/")) {
            throw new IllegalArgumentException("Expected explicit loopback test server");
        }
        String user = System.getenv().getOrDefault("REVIEW_TEST_DB_USERNAME", "root");
        String password = System.getenv().getOrDefault("REVIEW_TEST_DB_PASSWORD", "");
        String parameters = "?serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true";
        boolean created = false;
        try {
            try (var connection = DriverManager.getConnection(server + parameters, user, password);
                    var statement = connection.createStatement()) {
                statement.execute(
                        "CREATE DATABASE `"
                                + database
                                + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
                created = true;
            }
            String url = server + database + parameters;
            Flyway.configure().dataSource(url, user, password).target("50").load().migrate();
            var jdbc = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
            seed(jdbc);
            var migration = Flyway.configure().dataSource(url, user, password).load();
            // 模拟数据清理已提交、窗口表已删除，但点评字段DDL执行前进程失败。
            try (var db = DriverManager.getConnection(url, user, password)) {
                assertThatThrownBy(
                                () ->
                                        new db.migration.V51__remove_canteen_windows()
                                                .migrate(
                                                        context(
                                                                failBeforeReviewAlter(db),
                                                                migration)))
                        .isInstanceOf(java.sql.SQLException.class)
                        .hasMessage("Injected interruption before review DDL");
            }
            assertThat(count(jdbc, "SELECT COUNT(*) FROM _v51_window_reviews")).isEqualTo(2);
            assertThat(count(jdbc, "SELECT COUNT(*) FROM reviews WHERE window_id IS NOT NULL"))
                    .isZero();
            assertThat(migration.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(migration.info().current().getVersion().getVersion()).isEqualTo("51");
            verify(jdbc);
            migration.validate();
            assertThat(migration.migrate().migrationsExecuted).isZero();
            // 模拟DDL中断修复后重入：已删表/字段不会报错，业务数据与清理凭据保持正确。
            try (var db = DriverManager.getConnection(url, user, password)) {
                new db.migration.V51__remove_canteen_windows()
                        .migrate(
                                new org.flywaydb.core.api.migration.Context() {
                                    public org.flywaydb.core.api.configuration.Configuration
                                            getConfiguration() {
                                        return migration.getConfiguration();
                                    }

                                    public java.sql.Connection getConnection() {
                                        return db;
                                    }
                                });
            }
            verify(jdbc);
        } finally {
            if (created && database.matches("foodmap_retire_it_[a-f0-9]{32}")) {
                try (var connection =
                                DriverManager.getConnection(server + parameters, user, password);
                        var statement = connection.createStatement()) {
                    statement.execute("DROP DATABASE `" + database + "`");
                }
            }
        }
    }

    private void seed(JdbcTemplate jdbc) {
        jdbc.update(
                "INSERT INTO"
                    + " restaurants(id,name,restaurant_type,category_code,price_kind,created_by)"
                    + " VALUES(1,'普通餐厅','REGULAR','OTHER','UNKNOWN',11),(2,'测试食堂','CANTEEN',NULL,'UNKNOWN',11)");
        jdbc.update(
                "INSERT INTO canteen_windows(id,canteen_id,name,floor_number,created_by)"
                        + " VALUES(10,2,'旧窗口',1,11),(20,2,'已删窗口',2,11)");
        jdbc.update(
                "UPDATE canteen_windows SET deleted_at=CURRENT_TIMESTAMP(6),deleted_by=11 WHERE"
                        + " id=20");
        review(jdbc, 101, 1L, null, 8);
        review(jdbc, 102, 2L, null, 6);
        review(jdbc, 103, 2L, null, 8);
        review(jdbc, 201, null, 10L, 10);
        jdbc.update(
                "INSERT INTO"
                    + " review_request_receipts(author_user_id,client_request_id,review_id,restaurant_id,window_id)"
                    + " VALUES(11,UUID_TO_BIN(UUID()),202,NULL,20)");
        jdbc.update(
                "INSERT INTO"
                    + " review_replies(id,review_id,author_user_id,body,client_request_id,request_hash)"
                    + " VALUES(301,201,22,'旧窗口回复',UUID_TO_BIN(UUID()),UNHEX(REPEAT('ab',32)))");
        jdbc.update(
                "INSERT INTO"
                    + " reply_request_receipts(author_user_id,client_request_id,reply_id,review_id)"
                    + " VALUES(22,UUID_TO_BIN(UUID()),301,201),(22,UUID_TO_BIN(UUID()),302,202)");
        jdbc.update(
                "INSERT INTO content_likes(id,target_type,target_id,user_id)"
                    + " VALUES(401,'REVIEW',201,22),(402,'REPLY',302,33),(403,'REVIEW',101,22)");
        jdbc.update(
                "INSERT INTO"
                    + " content_reports(id,reporter_user_id,target_type,target_id,target_author_user_id,review_id,content_snapshot,reason)"
                    + " VALUES(501,22,'REVIEW',202,11,202,'已删除窗口点评快照','OTHER')");
        jdbc.update(
                "INSERT INTO"
                    + " notification_messages(recipient_user_id,notification_type,source_id,review_id,actor_user_id)"
                    + " VALUES(11,'REVIEW_LIKED',401,201,22),(11,'REVIEW_LIKED',403,101,22)");
        jdbc.update(
                "INSERT INTO"
                    + " notification_messages(recipient_user_id,notification_type,source_id,review_id,decision)"
                    + " VALUES(22,'REPORT_RESULT',501,202,'DISMISSED')");
        jdbc.update("INSERT INTO growth_accounts(user_id,experience) VALUES(11,45)");
        jdbc.update(
                "INSERT INTO"
                    + " growth_rewards(user_id,reward_type,business_key,source_type,source_id,amount)"
                    + " VALUES(11,'FIRST_STORE_REVIEW','WINDOW:10','REVIEW',201,10),(11,'FIRST_STORE_REVIEW','WINDOW:20','REVIEW',202,10),(11,'REVIEW_LIKED','LIKE:401','REVIEW',201,5),(11,'FIRST_STORE_REVIEW','1','REVIEW',101,20)");
        for (int id = 601; id <= 605; id++) {
            String purpose = id == 601 || id == 605 ? "REVIEW" : "WINDOW";
            String state = id == 604 ? "UPLOADING" : "BOUND";
            jdbc.update(
                    "INSERT INTO"
                        + " media_files(id,uploader_user_id,bucket_name,storage_key,content_type,byte_size,width,height,sha256,status,purpose,expires_at,bound_at)"
                        + " VALUES(?,11,'test-bucket',?,'image/jpeg',100,10,10,UNHEX(REPEAT('ab',32)),?,?,IF(?='UPLOADING',DATE_ADD(NOW(6),INTERVAL"
                        + " 1 HOUR),NULL),IF(?='BOUND',NOW(6),NULL))",
                    id,
                    "retire/test-" + id + ".jpg",
                    state,
                    purpose,
                    state,
                    state);
        }
        jdbc.update(
                "INSERT INTO review_photos(review_id,position,media_file_id)"
                        + " VALUES(201,0,601),(101,0,605)");
        jdbc.update("INSERT INTO canteen_window_photos(window_id,media_file_id) VALUES(10,602)");
        jdbc.update("UPDATE canteen_windows SET cover_file_id=603 WHERE id=20");
        jdbc.update(
                "INSERT INTO"
                    + " restaurant_review_statistics(restaurant_id,review_count,participant_count,recommend_count,taste_sum,value_sum,service_sum,ambience_sum,overall_score_sum)"
                    + " VALUES(1,1,1,1,8,8,8,8,8),(2,99,99,99,990,990,990,990,990)");
    }

    private void review(JdbcTemplate jdbc, int id, Long restaurant, Long window, int score) {
        jdbc.update(
                "INSERT INTO"
                    + " reviews(id,restaurant_id,window_id,author_user_id,taste_score,value_score,service_score,ambience_score,recommendation,body,client_request_id,request_hash,created_at)"
                    + " VALUES(?,?,?,11,?,?,?,?,'RECOMMENDED','迁移保留校验',UUID_TO_BIN(UUID()),UNHEX(REPEAT('ab',32)),DATE_ADD('2026-01-01',INTERVAL"
                    + " ? SECOND))",
                id,
                restaurant,
                window,
                score,
                score,
                score,
                score,
                id);
        jdbc.update(
                "INSERT INTO"
                    + " review_request_receipts(author_user_id,client_request_id,review_id,restaurant_id,window_id)"
                    + " SELECT author_user_id,client_request_id,id,restaurant_id,window_id FROM"
                    + " reviews WHERE id=?",
                id);
    }

    private void verify(JdbcTemplate jdbc) {
        assertThat(jdbc.queryForList("SELECT id FROM reviews ORDER BY id", Long.class))
                .containsExactly(101L, 102L, 103L);
        assertThat(
                        jdbc.queryForList(
                                "SELECT review_id FROM review_request_receipts ORDER BY review_id",
                                Long.class))
                .containsExactly(101L, 102L, 103L);
        for (String table :
                new String[] {"review_replies", "reply_request_receipts", "content_reports"}) {
            assertThat(count(jdbc, "SELECT COUNT(*) FROM " + table)).isZero();
        }
        assertThat(count(jdbc, "SELECT COUNT(*) FROM content_likes WHERE id=403")).isEqualTo(1);
        assertThat(count(jdbc, "SELECT COUNT(*) FROM content_likes")).isEqualTo(1);
        assertThat(count(jdbc, "SELECT COUNT(*) FROM notification_messages")).isEqualTo(1);
        assertThat(count(jdbc, "SELECT experience FROM growth_accounts WHERE user_id=11"))
                .isEqualTo(20);
        assertThat(count(jdbc, "SELECT COUNT(*) FROM growth_rewards")).isEqualTo(1);
        assertThat(
                        count(
                                jdbc,
                                "SELECT COUNT(*) FROM information_schema.tables WHERE"
                                    + " table_schema=DATABASE() AND (table_name IN"
                                    + " ('canteen_windows','window_review_statistics','canteen_window_photos')"
                                    + " OR table_name LIKE '_v51%')"))
                .isZero();
        assertThat(
                        count(
                                jdbc,
                                "SELECT COUNT(*) FROM information_schema.columns WHERE"
                                        + " table_schema=DATABASE() AND column_name='window_id'"))
                .isZero();
        assertThat(
                        count(
                                jdbc,
                                "SELECT COUNT(*) FROM media_files WHERE id BETWEEN 601 AND 604 AND"
                                        + " status='PENDING_DELETE' AND purpose='REVIEW'"))
                .isEqualTo(4);
        assertThat(
                        count(
                                jdbc,
                                "SELECT COUNT(*) FROM media_files WHERE id=604 AND"
                                        + " expires_at>DATE_ADD(NOW(6),INTERVAL 59 MINUTE)"))
                .isEqualTo(1);
        assertThat(count(jdbc, "SELECT COUNT(*) FROM media_files WHERE id=605 AND status='BOUND'"))
                .isEqualTo(1);
        assertThat(count(jdbc, "SELECT COUNT(*) FROM review_photos WHERE review_id=101"))
                .isEqualTo(1);
        assertThat(
                        count(
                                jdbc,
                                "SELECT review_count FROM restaurant_review_statistics WHERE"
                                        + " restaurant_id=1"))
                .isEqualTo(1);
        assertThat(
                        count(
                                jdbc,
                                "SELECT review_count FROM restaurant_review_statistics WHERE"
                                        + " restaurant_id=2"))
                .isEqualTo(2);
        assertThat(
                        count(
                                jdbc,
                                "SELECT participant_count FROM restaurant_review_statistics WHERE"
                                        + " restaurant_id=2"))
                .isEqualTo(1);
        assertThat(
                        count(
                                jdbc,
                                "SELECT taste_sum FROM restaurant_review_statistics WHERE"
                                        + " restaurant_id=2"))
                .isEqualTo(8);
        assertThat(
                        count(
                                jdbc,
                                "SELECT COUNT(*) FROM restaurant_search_outbox WHERE"
                                        + " restaurant_id=2 AND status='PENDING'"))
                .isPositive();
    }

    private long count(JdbcTemplate jdbc, String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    private org.flywaydb.core.api.migration.Context context(java.sql.Connection db, Flyway flyway) {
        return new org.flywaydb.core.api.migration.Context() {
            public org.flywaydb.core.api.configuration.Configuration getConfiguration() {
                return flyway.getConfiguration();
            }

            public java.sql.Connection getConnection() {
                return db;
            }
        };
    }

    private java.sql.Connection failBeforeReviewAlter(java.sql.Connection real) {
        return (java.sql.Connection)
                java.lang.reflect.Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class<?>[] {java.sql.Connection.class},
                        (proxy, method, args) -> {
                            if (method.getName().equals("createStatement")) {
                                var delegate = real.createStatement();
                                return java.lang.reflect.Proxy.newProxyInstance(
                                        getClass().getClassLoader(),
                                        new Class<?>[] {java.sql.Statement.class},
                                        (statement, operation, values) -> {
                                            if (operation.getName().equals("execute")
                                                    && values[0] instanceof String sql
                                                    && sql.stripLeading()
                                                            .startsWith("ALTER TABLE reviews")) {
                                                throw new java.sql.SQLException(
                                                        "Injected interruption before review DDL");
                                            }
                                            return invoke(operation, delegate, values);
                                        });
                            }
                            return invoke(method, real, args);
                        });
    }

    private Object invoke(java.lang.reflect.Method method, Object receiver, Object[] args)
            throws Throwable {
        try {
            return method.invoke(receiver, args);
        } catch (java.lang.reflect.InvocationTargetException failure) {
            throw failure.getCause();
        }
    }
}
