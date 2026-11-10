package com.ginkgo.foodmap.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ginkgo.foodmap.security.TestJwtKeys;
import com.ginkgo.foodmap.security.config.AuthenticationProperties;
import com.ginkgo.foodmap.security.service.LoginSessionService;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import tools.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 真实MySQL验证举报提交、快照、去重和归属边界；只替换Redis会话。 */
@ActiveProfiles("test")
@SpringBootTest(
        properties = {
            "spring.flyway.enabled=true",
            "spring.sql.init.mode=never",
            "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
            "spring.datasource.hikari.minimum-idle=0"
        })
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import(TestJwtKeys.class)
@EnabledIfSystemProperty(named = "moderation.integration", matches = "true")
class ContentReportApiIntegrationTest {
    private static String database;
    private static boolean created;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired JwtEncoder encoder;
    @Autowired AuthenticationProperties authentication;
    @MockitoBean LoginSessionService sessions;
    private String reporter;
    private String author;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws SQLException {
        database = "ginkgo_content_reports_it_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = connect(""); var statement = connection.createStatement()) {
            statement.executeUpdate(
                    "CREATE DATABASE `"
                            + database
                            + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            created = true;
        }
        registry.add("spring.datasource.url", () -> url(database));
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", ContentReportApiIntegrationTest::password);
    }

    @BeforeEach
    void setup() {
        reset(sessions);
        for (String table :
                List.of(
                        "content_reports",
                        "content_likes",
                        "growth_rewards",
                        "growth_accounts",
                        "restaurant_search_outbox",
                        "review_replies",
                        "review_photos",
                        "review_request_receipts",
                        "reviews",
                        "media_files",
                        "restaurant_review_statistics",
                        "restaurants",
                        "user_roles",
                        "users")) {
            jdbc.update("DELETE FROM " + table);
        }
        for (long id : List.of(11L, 22L)) {
            jdbc.update(
                    "INSERT INTO users(id,username,email,password_hash,email_verified) VALUES(?,?,?,?,TRUE)",
                    id,
                    "report_user_" + id,
                    "report" + id + "@example.test",
                    "private-password");
            jdbc.update(
                    "INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE code='ROLE_USER'",
                    id);
        }
        jdbc.update(
                "INSERT INTO restaurants(id,name,category_code,created_by) VALUES(1,'举报测试店','OTHER',11)");
        review(100, 1, 22, "被举报点评正文内容");
        reply(200, 100, 22, "被举报回复正文");
        when(sessions.isActive(anyString(), anyLong(), anyLong())).thenReturn(true);
        reporter = token(11);
        author = token(22);
    }

    @AfterAll
    static void cleanup() throws SQLException {
        if (!created) {
            return;
        }
        try (var connection = connect(""); var statement = connection.createStatement()) {
            statement.executeUpdate("DROP DATABASE `" + database + "`");
        }
    }

    @Test
    void createsReviewReportWithServerSnapshot() throws Exception {
        var result =
                report(reporter, "/api/reviews/100/reports", "ABUSE", "  含攻击性内容  ")
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.data.targetType").value("REVIEW"))
                        .andExpect(jsonPath("$.data.targetId").value("100"))
                        .andExpect(jsonPath("$.data.status").value("PENDING"))
                        .andExpect(jsonPath("$.data.duplicate").value(false))
                        .andReturn();
        String reportId =
                json.readTree(result.getResponse().getContentAsString())
                        .get("data")
                        .get("reportId")
                        .asText();
        assertThat(reportId).matches("[1-9][0-9]{0,18}");

        var row =
                jdbc.queryForMap(
                        "SELECT reporter_user_id,target_type,target_id,target_author_user_id,"
                                + "review_id,content_snapshot,reason,detail,status FROM content_reports");
        assertThat(number(row.get("reporter_user_id"))).isEqualTo(11L);
        assertThat(row.get("target_type")).isEqualTo("REVIEW");
        assertThat(number(row.get("target_id"))).isEqualTo(100L);
        assertThat(number(row.get("target_author_user_id"))).isEqualTo(22L);
        assertThat(number(row.get("review_id"))).isEqualTo(100L);
        assertThat(row.get("content_snapshot")).isEqualTo("被举报点评正文内容");
        assertThat(row.get("reason")).isEqualTo("ABUSE");
        assertThat(row.get("detail")).isEqualTo("含攻击性内容");
        assertThat(row.get("status")).isEqualTo("PENDING");
    }

    @Test
    void duplicateReportReturnsFirstRecordWithoutNewRow() throws Exception {
        var first =
                report(reporter, "/api/reviews/100/reports", "SPAM", null)
                        .andExpect(status().isCreated())
                        .andReturn();
        String firstId =
                json.readTree(first.getResponse().getContentAsString())
                        .get("data")
                        .get("reportId")
                        .asText();

        var second =
                report(reporter, "/api/reviews/100/reports", "ABUSE", "重复提交")
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.duplicate").value(true))
                        .andExpect(jsonPath("$.data.reportId").value(firstId))
                        .andReturn();
        assertThat(second.getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM content_reports", Long.class))
                .isEqualTo(1L);
        // 首次举报的事实不能被后续重复提交覆盖。
        assertThat(
                        jdbc.queryForObject(
                                "SELECT reason FROM content_reports WHERE id=" + firstId,
                                String.class))
                .isEqualTo("SPAM");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT detail FROM content_reports WHERE id=" + firstId,
                                String.class))
                .isNull();
    }

    @Test
    void rejectsReportingOwnContent() throws Exception {
        report(author, "/api/reviews/100/reports", "ABUSE", null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REPORT_SELF_NOT_ALLOWED"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM content_reports", Long.class))
                .isZero();
    }

    @Test
    void rejectsMissingContentWithoutLeakingVisibilityDetails() throws Exception {
        report(reporter, "/api/reviews/999/reports", "ABUSE", null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REPORT_TARGET_NOT_FOUND"));
    }

    @Test
    void rejectsInvalidReasonMalformedIdAndOversizedDetail() throws Exception {
        report(reporter, "/api/reviews/100/reports", "BOGUS", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REPORT_INVALID_INPUT"));
        report(reporter, "/api/reviews/0/reports", "ABUSE", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REPORT_INVALID_INPUT"));
        report(reporter, "/api/reviews/100/reports", "ABUSE", "长".repeat(201))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REPORT_INVALID_INPUT"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM content_reports", Long.class))
                .isZero();
    }

    @Test
    void reportsReplyWithParentReviewSnapshot() throws Exception {
        report(reporter, "/api/review-replies/200/reports", "IRRELEVANT", null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.targetType").value("REPLY"))
                .andExpect(jsonPath("$.data.targetId").value("200"));

        var row =
                jdbc.queryForMap(
                        "SELECT target_type,target_id,target_author_user_id,review_id,"
                                + "content_snapshot FROM content_reports");
        assertThat(row.get("target_type")).isEqualTo("REPLY");
        assertThat(number(row.get("target_id"))).isEqualTo(200L);
        assertThat(number(row.get("target_author_user_id"))).isEqualTo(22L);
        assertThat(number(row.get("review_id"))).isEqualTo(100L);
        assertThat(row.get("content_snapshot")).isEqualTo("被举报回复正文");
    }

    @Test
    void requiresAuthentication() throws Exception {
        mvc.perform(
                        post("/api/reviews/100/reports")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"ABUSE\"}"))
                .andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM content_reports", Long.class))
                .isZero();
    }

    private ResultActions report(String token, String path, String reason, String detail)
            throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("reason", reason);
        if (detail != null) {
            body.put("detail", detail);
        }
        return mvc.perform(
                post(path)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)));
    }

    private void review(long id, long restaurant, long author, String body) {
        jdbc.update(
                "INSERT INTO reviews(id,restaurant_id,author_user_id,taste_score,value_score,"
                        + "service_score,ambience_score,is_recommended,body,client_request_id,"
                        + "request_hash) VALUES(?,?,?,?,?,?,?,TRUE,?,UUID_TO_BIN(?),"
                        + "UNHEX(SHA2('report-test-hash',256)))",
                id,
                restaurant,
                author,
                8,
                8,
                8,
                8,
                body,
                UUID.randomUUID().toString());
    }

    private void reply(long id, long review, long author, String body) {
        jdbc.update(
                "INSERT INTO review_replies(id,review_id,author_user_id,body,client_request_id,"
                        + "request_hash) VALUES(?,?,?,?,UUID_TO_BIN(?),"
                        + "UNHEX(SHA2('report-test-hash',256)))",
                id,
                review,
                author,
                body,
                UUID.randomUUID().toString());
    }

    private static long number(Object value) {
        return ((Number) Objects.requireNonNull(value)).longValue();
    }

    private String token(long user) {
        var now = Instant.now();
        var jwt = authentication.getJwt();
        var claims =
                JwtClaimsSet.builder()
                        .issuer(jwt.getIssuer())
                        .audience(List.of(jwt.getAudience()))
                        .subject(Long.toString(user))
                        .issuedAt(now.minusSeconds(60))
                        .expiresAt(now.plusSeconds(600))
                        .id(UUID.randomUUID().toString())
                        .claim("credential_version", 0)
                        .build();
        return encoder.encode(
                        JwtEncoderParameters.from(
                                JwsHeader.with(SignatureAlgorithm.RS256).build(), claims))
                .getTokenValue();
    }

    private static String password() {
        return Objects.requireNonNull(
                System.getenv("MODERATION_TEST_DB_PASSWORD"),
                "Set isolated moderation test password");
    }

    private static String url(String name) {
        String server =
                System.getenv()
                        .getOrDefault(
                                "MODERATION_TEST_DB_SERVER", "jdbc:mysql://127.0.0.1:3306/");
        if (!server.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]{1,5}/")) {
            throw new IllegalArgumentException("Loopback only");
        }
        return server
                + name
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC"
                + "&forceConnectionTimeZoneToSession=true&useSSL=false"
                + "&allowPublicKeyRetrieval=true";
    }

    private static Connection connect(String name) throws SQLException {
        return DriverManager.getConnection(url(name), "root", password());
    }
}
