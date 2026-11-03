package com.ginkgo.foodmap.file;

import com.ginkgo.foodmap.file.image.JpegImageProcessor;

import com.ginkgo.foodmap.restaurant.service.RestaurantReviewApplicationService;
import com.ginkgo.foodmap.security.TestJwtKeys;
import com.ginkgo.foodmap.security.config.AuthenticationProperties;
import com.ginkgo.foodmap.security.service.LoginSessionService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;
import java.sql.*;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 显式oss.live开关：真实RAM/OSS、隔离MySQL与JWT上传测试；不向业务库发布点评，不记录凭证。 */
@ActiveProfiles("test")
@SpringBootTest(properties={"spring.flyway.enabled=true","spring.sql.init.mode=never",
    "app.file.oss.bucket=test-oss-bucket","spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver","spring.datasource.hikari.minimum-idle=0"})
@AutoConfigureMockMvc(print=org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE)
@Import(TestJwtKeys.class)
@EnabledIfSystemProperty(named="oss.live",matches="true")
class OssLiveUploadIntegrationTest {
    private static String database;
    private static boolean created;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired JwtEncoder encoder;
    @Autowired AuthenticationProperties authentication;
    @Autowired com.ginkgo.foodmap.file.config.OssProperties ossProperties;
    @MockitoBean LoginSessionService sessions;
    @Autowired com.ginkgo.foodmap.file.storage.OssStorageClient storage;
    @Autowired com.ginkgo.foodmap.file.mapper.MediaFileMapper files;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    private String owner,other;


    /** 测试仅访问显式回环实例，创建成功才登记可清理随机库。 */
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) throws SQLException {
        var configPath=java.util.Objects.requireNonNull(System.getenv("OSS_LIVE_CONFIG"), "Set OSS_LIVE_CONFIG explicitly");
        try {
            var source=new org.springframework.boot.env.YamlPropertySourceLoader().load("oss-live",new org.springframework.core.io.FileSystemResource(configPath)).getFirst();
            registry.add("app.file.oss.enabled",()->true);
            for(var name:List.of("endpoint","region","bucket","access-key-id","access-key-secret","restaurant-prefix")) {
                var key="app.file.oss."+name;
                var value=source.getProperty(key);
                if (value != null) registry.add(key,()->value);
            }
            registry.add("app.file.oss.cleanup-delay-ms",()->3600000);
        } catch(java.io.IOException e) { throw new IllegalStateException("Cannot read live OSS config"); }
        database="ginkgo_oss_live_it_"+UUID.randomUUID().toString().replace("-", "");
        try(var c=connect("");var s=c.createStatement()) {
            s.executeUpdate("CREATE DATABASE `"+database+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");created=true;
        }
        registry.add("spring.datasource.url",()->url(database));
        registry.add("spring.datasource.username",()->"root");
        registry.add("spring.datasource.password",OssLiveUploadIntegrationTest::password);
    }
    @BeforeEach void setup() {
        reset(sessions);
        jdbc.update("DELETE FROM review_photos");
        jdbc.update("DELETE FROM media_files");
        jdbc.update("DELETE FROM reviews");jdbc.update("DELETE FROM restaurant_review_statistics");
        jdbc.update("DELETE FROM restaurants");jdbc.update("DELETE FROM user_roles");jdbc.update("DELETE FROM users");
        for(long id:List.of(11L,22L)) {
            jdbc.update("INSERT INTO users(id,username,email,password_hash,email_verified) VALUES(?,?,?,?,TRUE)",id,"reviewer_"+id,"reviewer"+id+"@example.test","test-only");
            jdbc.update("INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE code='ROLE_USER'",id);
        }
        jdbc.update("INSERT INTO restaurants(id,name,category_code,created_by) VALUES(1,'发布店','OTHER',11),(2,'另一店','OTHER',11)");
        when(sessions.isActive(anyString(),anyLong(),anyLong())).thenReturn(true);
        owner=token(11,false);other=token(22,false);
    }
    @AfterAll static void cleanup() throws SQLException {
        if(!created)return;
        if(!database.matches("ginkgo_oss_live_it_[a-f0-9]{32}"))throw new IllegalStateException("Unexpected database");
        try(var c=connect("");var s=c.createStatement()){s.executeUpdate("DROP DATABASE `"+database+"`");}
    }
    /** 验证真实multipart、JWT、MySQL与私有OSS，包含本人取消和他人越权，不发布点评。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"REVIEW","RESTAURANT"})
    void uploadsReadsAndImmediatelyCancelsRealImage(String purpose) throws Exception {
        var response=mvc.perform(multipart("/api/files/images")
            .file(new org.springframework.mock.web.MockMultipartFile("file","test.jpg","image/jpeg",JpegImageProcessorTest.jpeg(48,32)))
            .param("purpose",purpose)
            .header("Authorization","Bearer "+owner))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.data.width").value(48)).andExpect(jsonPath("$.data.height").value(32))
            .andReturn().getResponse().getContentAsString();
        String id=json.readTree(response).get("data").get("fileId").asString();
        var row=files.find(Long.parseLong(id));
        assertThat(row.getStatus()).isEqualTo("TEMPORARY");
        assertThat(row.getPurpose()).isEqualTo(purpose);
        // 按实际配置核验前缀，兼容同级目录与受限RAM下的子目录部署。
        assertThat(row.getStorageKey()).startsWith(purpose.equals("RESTAURANT")
                ? ossProperties.getRestaurantPrefix() : ossProperties.getPrefix());
        byte[] downloaded=storage.read(row.getBucketName(),row.getStorageKey());
        assertThat(javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(downloaded)).getWidth()).isEqualTo(48);
        mvc.perform(delete("/api/files/images/"+id).header("Authorization","Bearer "+other)).andExpect(status().isNotFound());
        mvc.perform(delete("/api/files/images/"+id).header("Authorization","Bearer "+owner)).andExpect(status().isNoContent());
        assertThat(files.find(Long.parseLong(id))).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reviews",Long.class)).isZero();
        System.out.println("LIVE_OSS_API_OK upload=201 owner_cancel=204 other_cancel=404 reviews=0");
    }
    /** 仅清理本次随机测试库登记的云对象，不扫描或删除其他业务文件。 */
    @AfterEach void cleanupCloud() {
        for(var row:jdbc.queryForList("SELECT id,bucket_name,storage_key FROM media_files")) {
            storage.delete((String)row.get("bucket_name"),(String)row.get("storage_key"));
            jdbc.update("DELETE FROM media_files WHERE id=?",row.get("id"));
        }
    }
    private String token(long user,boolean expired) { return issueToken(user,expired); }
    private String issueToken(long user,boolean expired) {
        var now=Instant.now();var p=authentication.getJwt();
        var claims=JwtClaimsSet.builder().issuer(p.getIssuer()).audience(List.of(p.getAudience())).subject(Long.toString(user))
            .issuedAt(now.minusSeconds(120)).expiresAt(now.plusSeconds(expired?-60:600)).id(UUID.randomUUID().toString()).claim("credential_version",0).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(),claims)).getTokenValue();
    }
    private static String password(){return Objects.requireNonNull(System.getenv("REVIEW_TEST_DB_PASSWORD"),"Set isolated test password");}
    private static String url(String name) {
        var server=System.getenv().getOrDefault("REVIEW_TEST_DB_SERVER","jdbc:mysql://127.0.0.1:3306/");
        if(!server.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]{1,5}/"))throw new IllegalArgumentException("Loopback only");
        return server+name+"?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC&forceConnectionTimeZoneToSession=true&useSSL=false&allowPublicKeyRetrieval=true";
    }
    private static Connection connect(String db)throws SQLException{return DriverManager.getConnection(url(db),"root",password());}
}
