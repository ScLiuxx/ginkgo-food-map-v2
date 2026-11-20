package com.foodmap.restaurant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.DriverManager;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 非空V46到V47专项升级验证；只操作当前测试创建的随机回环库，绝不迁移开发库。 */
@EnabledIfSystemProperty(named = "restaurant.integration", matches = "true")
class RestaurantLinkExistingSchemaIntegrationTest {
    @Test
    void upgradesHistoricalDecisionsPreservingSnapshotsAndEnforcesModeCombinations()
            throws Exception {
        String database = "foodmap_link_schema_it_" + UUID.randomUUID().toString().replace("-", "");
        String server =
                System.getenv()
                        .getOrDefault("RESTAURANT_TEST_DB_SERVER", "jdbc:mysql://127.0.0.1:3306/");
        if (!server.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]{1,5}/")) {
            throw new IllegalArgumentException("Expected loopback database server");
        }
        String user = System.getenv().getOrDefault("RESTAURANT_TEST_DB_USERNAME", "root");
        String password = Objects.requireNonNull(System.getenv("RESTAURANT_TEST_DB_PASSWORD"));
        String options =
                "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true";
        try (var connection = DriverManager.getConnection(server + options, user, password);
                var statement = connection.createStatement()) {
            statement.executeUpdate(
                    "CREATE DATABASE `"
                            + database
                            + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            try {
                String url = server + database + options;
                var db = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
                Flyway.configure()
                        .dataSource(url, user, password)
                        .locations("classpath:db/migration")
                        .target("46")
                        .load()
                        .migrate();
                db.update(
                        "INSERT INTO restaurants(id,name,category_code)"
                                + " VALUES(991,'schema-shop','OTHER')");
                for (String type : List.of("CREATE", "UPDATE")) {
                    long id = "CREATE".equals(type) ? 1 : 2;
                    db.update(
                            "INSERT INTO"
                                + " restaurant_change_requests(id,restaurant_id,request_type,submitted_by,client_request_id,request_hash,proposed_data,status,reviewed_by,reviewed_at,approved_data,before_data)"
                                + " VALUES(?,991,?,11,UUID(),UNHEX(SHA2('fixture',256)),JSON_OBJECT('name','proposal'),'APPROVED',33,CURRENT_TIMESTAMP(6),JSON_OBJECT('name','approved'),?)",
                            id,
                            type,
                            "CREATE".equals(type) ? null : "{\"name\":\"before\"}");
                    db.update(
                            "INSERT INTO"
                                + " notification_messages(recipient_user_id,notification_type,source_id,request_id,request_type,decision,restaurant_name)"
                                + " VALUES(11,'RESTAURANT_DECISION',?,?,?,'APPROVED','schema-shop')",
                            id,
                            id,
                            type);
                }
                db.update(
                        "INSERT INTO"
                            + " notification_messages(recipient_user_id,notification_type,source_id,actor_user_id)"
                            + " VALUES(11,'USER_FOLLOWED',3,22)");
                var beforeRequests =
                        db.queryForList(
                                "SELECT"
                                    + " id,proposed_data,approved_data,before_data,HEX(request_hash)"
                                    + " h,reviewed_at FROM restaurant_change_requests ORDER BY id");
                var beforeNotices =
                        db.queryForList(
                                "SELECT id,decision,read_at,restaurant_name FROM"
                                        + " notification_messages ORDER BY id");
                var flyway =
                        Flyway.configure()
                                .dataSource(url, user, password)
                                .locations("classpath:db/migration")
                                .target("47")
                                .load();
                assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
                assertThat(
                                db.queryForList(
                                        "SELECT approval_mode FROM restaurant_change_requests ORDER"
                                                + " BY id",
                                        String.class))
                        .containsExactly("CREATED", "UPDATED");
                assertThat(
                                db.queryForList(
                                        "SELECT approval_mode FROM notification_messages ORDER BY"
                                                + " id",
                                        String.class))
                        .containsExactly("CREATED", "UPDATED", null);
                assertThat(
                                db.queryForList(
                                        "SELECT"
                                            + " id,proposed_data,approved_data,before_data,HEX(request_hash)"
                                            + " h,reviewed_at FROM restaurant_change_requests ORDER"
                                            + " BY id"))
                        .isEqualTo(beforeRequests);
                assertThat(
                                db.queryForList(
                                        "SELECT id,decision,read_at,restaurant_name FROM"
                                                + " notification_messages ORDER BY id"))
                        .isEqualTo(beforeNotices);
                assertThat(flyway.migrate().migrationsExecuted).isZero();
                flyway.validate();
                db.update(
                        "UPDATE restaurant_change_requests SET approval_mode='LINKED_EXISTING'"
                                + " WHERE id=1");
                db.update(
                        "UPDATE notification_messages SET approval_mode='LINKED_EXISTING' WHERE"
                                + " request_id=1");
                for (String sql :
                        List.of(
                                "UPDATE restaurant_change_requests SET approval_mode='UPDATED'"
                                        + " WHERE id=1",
                                "UPDATE restaurant_change_requests SET"
                                        + " approval_mode='LINKED_EXISTING' WHERE id=2",
                                "UPDATE restaurant_change_requests SET approval_mode='UNKNOWN'"
                                        + " WHERE id=1",
                                "UPDATE notification_messages SET approval_mode='UPDATED' WHERE"
                                        + " request_id=1",
                                "UPDATE notification_messages SET approval_mode='LINKED_EXISTING'"
                                        + " WHERE request_id=2",
                                "UPDATE notification_messages SET approval_mode='LINKED_EXISTING'"
                                        + " WHERE notification_type='USER_FOLLOWED'")) {
                    assertThatThrownBy(() -> db.update(sql))
                            .isInstanceOf(DataAccessException.class);
                }
            } finally {
                // 数据库名仅由本方法生成，连接建立且创建成功后才进入清理分支。
                if (database.matches("foodmap_link_schema_it_[a-f0-9]{32}")) {
                    statement.executeUpdate("DROP DATABASE `" + database + "`");
                }
            }
        }
    }
}
