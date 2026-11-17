package com.foodmap.restaurant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.util.Objects;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** V43→V44真实MySQL验证，所有DDL和统计写入仅发生在本次创建的随机隔离库。 */
@EnabledIfSystemProperty(named = "reputation.integration", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RestaurantReputationMySqlIntegrationTest {
    private String database;
    private JdbcTemplate jdbc;
    private DriverManagerDataSource source;

    @BeforeAll
    void initialize() throws Exception {
        database = "foodmap_reputation_it_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(url(""), username(), password());
                var statement = connection.createStatement()) {
            statement.executeUpdate("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
        Flyway.configure().dataSource(url(database), username(), password()).target("43").load().migrate();
        source = new DriverManagerDataSource(url(database), username(), password());
        jdbc = new JdbcTemplate(source);
        jdbc.update("INSERT INTO restaurants(id,name,category_code,created_by) VALUES(901,'口碑迁移夹具','OTHER',1)");
        jdbc.update("INSERT INTO restaurant_review_statistics(restaurant_id,review_count,participant_count,taste_sum,value_sum,service_sum,ambience_sum,overall_score_sum) VALUES(901,1,1,10,10,10,10,10)");
        var before = jdbc.queryForMap("SELECT * FROM restaurant_review_statistics WHERE restaurant_id=901");
        migration().migrate();
        var after = jdbc.queryForMap("SELECT * FROM restaurant_review_statistics WHERE restaurant_id=901");
        after.remove("reputation_score");
        assertThat(after).isEqualTo(before);
    }

    /** 只删除本次创建且严格匹配的数据库，不允许测试误删开发业务库。 */
    @AfterAll
    void cleanup() throws Exception {
        if (database != null && database.matches("foodmap_reputation_it_[a-f0-9]{32}")) {
            try (var connection = DriverManager.getConnection(url(""), username(), password());
                    var statement = connection.createStatement()) {
                statement.executeUpdate("DROP DATABASE `" + database + "`");
            }
        }
    }

    @Test
    void backfillsWithoutChangingRawStatisticsAndRepeatsSafely() {
        update(1, 10);
        assertThat(score()).isEqualByComparingTo("3.75");
        assertThat(migration().migrate().migrationsExecuted).isZero();
        migration().validate();
        assertThat(jdbc.queryForObject("SELECT EXTRA FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='restaurant_review_statistics' AND column_name='reputation_score'", String.class))
                .isEqualTo("STORED GENERATED");
    }

    @Test
    void usesParticipantsInsteadOfReviewCountAndReplacesContributionsAtomically() {
        update(20, 180);
        assertThat(score()).isEqualByComparingTo("4.3");
        jdbc.update("UPDATE restaurant_review_statistics SET review_count=review_count+1,taste_sum=taste_sum-2 WHERE restaurant_id=901");
        assertThat(score()).isEqualByComparingTo("4.272");
        jdbc.update("UPDATE restaurant_review_statistics SET review_count=review_count+100 WHERE restaurant_id=901");
        assertThat(score()).isEqualByComparingTo("4.272");
    }

    @Test
    void noParticipantsMeansNoRatingAndLowScoresShrinkTowardPrior() {
        update(0, 0);
        assertThat(score()).isNull();
        update(1, 0);
        assertThat(score()).isEqualByComparingTo("2.91666667");
    }

    @Test
    void generatedScoreCannotBeWrittenAndRollsBackWithItsStatistics() {
        update(1, 10);
        assertThatThrownBy(() -> jdbc.update("UPDATE restaurant_review_statistics SET reputation_score=5 WHERE restaurant_id=901"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.executeWithoutResult(status -> {
            update(20, 180);
            assertThat(score()).isEqualByComparingTo("4.3");
            status.setRollbackOnly();
        });
        assertThat(score()).isEqualByComparingTo("3.75");
    }

    private void update(int participants, int sum) {
        jdbc.update("UPDATE restaurant_review_statistics SET review_count=?,participant_count=?,recommend_count=0,taste_sum=?,value_sum=?,service_sum=?,ambience_sum=?,overall_score_sum=? WHERE restaurant_id=901",
                participants, participants, sum, sum, sum, sum, sum);
    }

    private BigDecimal score() {
        return jdbc.queryForObject("SELECT reputation_score FROM restaurant_review_statistics WHERE restaurant_id=901", BigDecimal.class);
    }

    private Flyway migration() {
        return Flyway.configure().dataSource(url(database), username(), password()).load();
    }

    private String url(String name) {
        var server = System.getenv().getOrDefault("RANKING_TEST_DB_SERVER", "jdbc:mysql://127.0.0.1:3306/");
        if (!server.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]{1,5}/")) {
            throw new IllegalArgumentException("Reputation tests require loopback MySQL");
        }
        return server + name + "?serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true";
    }

    private String username() {
        return System.getenv().getOrDefault("RANKING_TEST_DB_USERNAME", "root");
    }

    private String password() {
        return Objects.requireNonNull(System.getenv("RANKING_TEST_DB_PASSWORD"));
    }
}
