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

/** 真实MySQL/Flyway/RS256测试；只替换Redis会话依赖，不绕过数据库账号状态。 */
@ActiveProfiles("test")
@SpringBootTest(properties={"spring.flyway.enabled=true","spring.sql.init.mode=never",
    "app.file.oss.bucket=test-oss-bucket","spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver","spring.datasource.hikari.minimum-idle=0"})
@AutoConfigureMockMvc(print=org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE)
@Import(TestJwtKeys.class)
@EnabledIfSystemProperty(named="review.integration",matches="true")
class ImageUploadIntegrationTest {
    private static String database;
    private static boolean created;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired JwtEncoder encoder;
    @Autowired AuthenticationProperties authentication;
    @MockitoBean LoginSessionService sessions;
    @MockitoBean com.ginkgo.foodmap.file.storage.OssStorageClient storage;
    @Autowired com.ginkgo.foodmap.file.mapper.MediaFileMapper files;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    private String owner,other;


    /** 测试仅访问显式回环实例，创建成功才登记可清理随机库。 */
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) throws SQLException {
        database="ginkgo_file_it_"+UUID.randomUUID().toString().replace("-", "");
        try(var c=connect("");var s=c.createStatement()) {
            s.executeUpdate("CREATE DATABASE `"+database+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");created=true;
        }
        registry.add("spring.datasource.url",()->url(database));
        registry.add("spring.datasource.username",()->"root");
        registry.add("spring.datasource.password",ImageUploadIntegrationTest::password);
    }
    @BeforeEach void setup() {
        reset(sessions,storage);
        jdbc.update("DELETE FROM review_request_receipts");
        jdbc.update("DELETE FROM review_photos");
        jdbc.update("DELETE FROM restaurant_change_photos");
        jdbc.update("DELETE FROM restaurant_change_requests");
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
        if(!database.matches("ginkgo_file_it_[a-f0-9]{32}"))throw new IllegalStateException("Unexpected database");
        try(var c=connect("");var s=c.createStatement()){s.executeUpdate("DROP DATABASE `"+database+"`");}
    }
    private org.springframework.test.web.servlet.ResultActions upload(String token) throws Exception {
        return mvc.perform(multipart("/api/files/images").file(new org.springframework.mock.web.MockMultipartFile(
            "file","untrusted-name.png","image/png",JpegImageProcessorTest.jpeg(80,60)))
            .header("Authorization","Bearer "+token));
    }
    private String uploadId() throws Exception {
        var response=upload(owner).andExpect(status().isCreated()).andExpect(jsonPath("$.data.width").value(80))
            .andExpect(jsonPath("$.data.height").value(60)).andExpect(jsonPath("$.data.expiresAt").isNotEmpty())
            .andExpect(jsonPath("$.data.bucketName").doesNotExist()).andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("data").get("fileId").asString();
    }
    private com.ginkgo.foodmap.file.job.MediaCleanupJob cleanupJob() {
        return new com.ginkgo.foodmap.file.job.MediaCleanupJob(new com.ginkgo.foodmap.file.service.MediaCleanupService(files,storage,transactions));
    }
    @Test void verifiesContentAndRegistersTemporaryImage() throws Exception {
        String id=uploadId();
        assertThat(jdbc.queryForObject("SELECT status FROM media_files WHERE id=?",String.class,id)).isEqualTo("TEMPORARY");
        assertThat(jdbc.queryForObject("SELECT uploader_user_id FROM media_files WHERE id=?",Long.class,id)).isEqualTo(11);
        verify(storage).put(eq("test-oss-bucket"),matches("review-images/[a-f0-9-]+\\.jpg"),any(byte[].class));
    }
    @Test void anonymousAndMissingFileAreRejected() throws Exception {
        mvc.perform(multipart("/api/files/images").file("file",new byte[]{1})).andExpect(status().isUnauthorized());
        mvc.perform(multipart("/api/files/images").header("Authorization","Bearer "+owner))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("FILE_INVALID_IMAGE"));
        verify(storage,never()).put(anyString(),anyString(),any());
    }
    @Test void rejectsFakeImageWithoutRegisteringObject() throws Exception {
        mvc.perform(multipart("/api/files/images").file("file","<svg/>".getBytes()).header("Authorization","Bearer "+owner))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("FILE_INVALID_IMAGE"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM media_files",Long.class)).isZero();
    }
    @Test void failedOssUploadRetainsDurableCleanupRecord() throws Exception {
        doThrow(new com.ginkgo.foodmap.common.exception.BusinessException(com.ginkgo.foodmap.file.exception.FileErrorCode.UNAVAILABLE))
            .when(storage).put(anyString(),anyString(),any());
        doThrow(new IllegalStateException("delete-test-only")).when(storage).delete(anyString(),anyString());
        upload(owner).andExpect(status().isServiceUnavailable());
        assertThat(jdbc.queryForObject("SELECT status FROM media_files",String.class)).isEqualTo("PENDING_DELETE");
        cleanupJob().cleanup();
        verify(storage,atLeastOnce()).delete(anyString(),anyString());
        reset(storage);
        jdbc.update("UPDATE media_files SET expires_at=CURRENT_TIMESTAMP-INTERVAL 1 SECOND");
        cleanupJob().cleanup();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM media_files",Long.class)).isZero();
    }
    @Test void cancellationChecksOwnershipAndRetriesFailedDeletion() throws Exception {
        String id=uploadId();
        mvc.perform(delete("/api/files/images/"+id).header("Authorization","Bearer "+other)).andExpect(status().isNotFound());
        doThrow(new IllegalStateException("delete-test-only")).when(storage).delete(anyString(),anyString());
        mvc.perform(delete("/api/files/images/"+id).header("Authorization","Bearer "+owner)).andExpect(status().isAccepted());
        mvc.perform(delete("/api/files/images/"+id).header("Authorization","Bearer "+owner)).andExpect(status().isAccepted());
        doThrow(new IllegalStateException("test-only")).when(storage).delete(anyString(),anyString());
        cleanupJob().cleanup();
        assertThat(jdbc.queryForObject("SELECT status FROM media_files WHERE id=?",String.class,id)).isEqualTo("PENDING_DELETE");
        reset(storage);
        cleanupJob().cleanup();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM media_files",Long.class)).isZero();
        mvc.perform(delete("/api/files/images/"+id).header("Authorization","Bearer "+owner)).andExpect(status().isNoContent());
    }
    @Test void boundFilesCannotBeCancelledOrExpired() throws Exception {
        String id=uploadId();
        jdbc.update("UPDATE media_files SET status='BOUND',expires_at=NULL,bound_at=CURRENT_TIMESTAMP WHERE id=?",id);
        mvc.perform(delete("/api/files/images/"+id).header("Authorization","Bearer "+owner)).andExpect(status().isConflict());
        cleanupJob().cleanup();
        verify(storage,never()).delete(anyString(),anyString());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM media_files",Long.class)).isEqualTo(1);
    }
    @Test void expiredTemporaryFilesAreCleanedButValidOnesRemain() throws Exception {
        String expired=uploadId(); String valid=uploadId();
        jdbc.update("UPDATE media_files SET expires_at=CURRENT_TIMESTAMP-INTERVAL 1 SECOND WHERE id=?",expired);
        cleanupJob().cleanup();
        assertThat(files.find(Long.parseLong(expired))).isNull();
        assertThat(files.find(Long.parseLong(valid))).isNotNull();
    }
    /** 商户上传沿用同Bucket私有对象，但用途及目录隔离，未提交文件可取消并清理。 */
    private String uploadMerchant(String actor) throws Exception {
        var response=mvc.perform(multipart("/api/files/images").file(new org.springframework.mock.web.MockMultipartFile(
            "file","merchant.jpg","image/jpeg",JpegImageProcessorTest.jpeg(80,60)))
            .param("purpose","RESTAURANT").header("Authorization","Bearer "+actor))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("data").get("fileId").asString();
    }
    private Map<String,Object> merchantInput(List<String> ids) {
        var input=new LinkedHashMap<String,Object>();
        input.put("clientRequestId",UUID.randomUUID().toString()); input.put("name","待审图片商户");
        input.put("categoryCode","OTHER");input.put("latitude",30.67);input.put("longitude",104.1);
        input.put("locationConfirmed",true);input.put("photoIds",ids);return input;
    }
    private org.springframework.test.web.servlet.ResultActions submitMerchant(String endpoint, Map<String,Object> body) throws Exception {
        return mvc.perform(post(endpoint).header("Authorization","Bearer "+owner)
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }
    @Test void merchantPhotosBindInOrderAndReplayWithoutPublishing() throws Exception {
        String first=uploadMerchant(owner),second=uploadMerchant(owner);
        verify(storage,times(2)).put(eq("test-oss-bucket"),matches("restaurant-images/[a-f0-9-]+\\.jpg"),any(byte[].class));
        var input=merchantInput(List.of(second,first));
        String body=submitMerchant("/api/restaurant-submissions",input).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id=json.readTree(body).get("data").get("id").asString();
        submitMerchant("/api/restaurant-submissions",input).andExpect(status().isCreated()).andExpect(jsonPath("$.data.id").value(id));
        assertThat(jdbc.queryForList("SELECT media_file_id FROM restaurant_change_photos WHERE request_id=? ORDER BY position",String.class,id)).containsExactly(second,first);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM media_files WHERE status='BOUND' AND purpose='RESTAURANT'",Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM restaurants",Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM review_photos",Long.class)).isZero();
        mvc.perform(delete("/api/files/images/"+first).header("Authorization","Bearer "+owner)).andExpect(status().isConflict());
        cleanupJob().cleanup();verify(storage,never()).delete(anyString(),anyString());
        input.put("photoIds",List.of(first,second));
        submitMerchant("/api/restaurant-submissions",input).andExpect(status().isConflict());
    }
    @Test void correctionPhotosStayPrivateAndCannotCrossBindToReviews() throws Exception {
        String id=uploadMerchant(owner);
        publish(reviewInput(List.of(id),UUID.randomUUID().toString())).andExpect(status().isConflict());
        assertThat(files.find(Long.parseLong(id)).getStatus()).isEqualTo("TEMPORARY");
        submitMerchant("/api/restaurants/1/change-requests",merchantInput(List.of(id))).andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT name FROM restaurants WHERE id=1",String.class)).isEqualTo("发布店");
        // 不提供按文件ID公开读取；待审图片也不能通过点评读取服务泄漏。
        var service=new com.ginkgo.foodmap.file.service.MediaFileService(files,storage);
        org.junit.jupiter.api.Assertions.assertThrows(com.ginkgo.foodmap.common.exception.BusinessException.class,
            ()->service.readBound(Long.parseLong(id)));
        verify(storage,never()).read(anyString(),anyString());
    }
    @Test void invalidMerchantPhotoRollsBackAllBindingsAndRequest() throws Exception {
        String valid=uploadMerchant(owner),otherFile=uploadMerchant(other),review=uploadId(),expired=uploadMerchant(owner);
        jdbc.update("UPDATE media_files SET expires_at=CURRENT_TIMESTAMP-INTERVAL 1 SECOND WHERE id=?",expired);
        for(String invalid:List.of(otherFile,review,expired,"999999")) {
            submitMerchant("/api/restaurant-submissions",merchantInput(List.of(valid,invalid))).andExpect(status().isConflict());
            assertThat(files.find(Long.parseLong(valid)).getStatus()).isEqualTo("TEMPORARY");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM restaurant_change_requests",Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM restaurant_change_photos",Long.class)).isZero();
        }
        submitMerchant("/api/restaurant-submissions",merchantInput(List.of(valid,valid))).andExpect(status().isBadRequest());
        submitMerchant("/api/restaurant-submissions",merchantInput(List.of("9999999999999999999"))).andExpect(status().isBadRequest());
        submitMerchant("/api/restaurant-submissions",merchantInput(List.of(valid,review,expired,otherFile,"5","6"))).andExpect(status().isBadRequest());
        mvc.perform(delete("/api/files/images/"+valid).header("Authorization","Bearer "+owner)).andExpect(status().isNoContent());
        verify(storage).delete(eq("test-oss-bucket"),startsWith("restaurant-images/"));
    }
    @Test void concurrentMerchantPhotoSubmissionUsesOneBinding() throws Exception {
        String id=uploadMerchant(owner);var input=merchantInput(List.of(id));var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var a=executor.submit(()->{start.await();return submitMerchant("/api/restaurant-submissions",input).andReturn();});
            var b=executor.submit(()->{start.await();return submitMerchant("/api/restaurant-submissions",input).andReturn();});
            start.countDown();
            assertThat(a.get(15,TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(201);
            assertThat(b.get(15,TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(201);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM restaurant_change_photos",Long.class)).isEqualTo(1);
    }

    private String token(long user,boolean expired) {
        return issueToken(user,expired);
    }

    /** 真正的图片绑定/重放/读取链路，只替换云端传输，不绕过JWT和数据库。 */
    private Map<String,Object> reviewInput(List<String> ids,String uuid) {
        return Map.of("clientRequestId",uuid,"scores",Map.of("taste",7,"value",7,"service",7,"ambience",7),
            "recommended",true,"body","带图点评的真实数据库测试正文","photoIds",ids);
    }
    private org.springframework.test.web.servlet.ResultActions publish(Map<String,Object> body) throws Exception {
        return mvc.perform(post("/api/restaurants/1/reviews").header("Authorization","Bearer "+owner)
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }
    @Test void bindsOrderedPhotosAndReplaysBeforeValidatingTemporaryState() throws Exception {
        String first=uploadId(),second=uploadId();
        var input=reviewInput(List.of(second,first),UUID.randomUUID().toString());
        var response=publish(input).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String review=json.readTree(response).get("data").get("reviewId").asString();
        publish(input).andExpect(status().isOk()).andExpect(jsonPath("$.data.replayed").value(true));
        assertThat(jdbc.queryForList("SELECT media_file_id FROM review_photos ORDER BY position",Long.class))
            .containsExactly(Long.parseLong(second),Long.parseLong(first));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM media_files WHERE status='BOUND'",Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT review_count FROM restaurant_review_statistics WHERE restaurant_id=1",Long.class)).isEqualTo(1);
        when(storage.read(anyString(),anyString())).thenReturn(JpegImageProcessorTest.jpeg(5,5));
        mvc.perform(get("/api/reviews/"+review+"/photos/0")).andExpect(status().isOk()).andExpect(content().contentType("image/jpeg"));
        input=reviewInput(List.of(first,second),(String)input.get("clientRequestId"));
        publish(input).andExpect(status().isConflict());
        jdbc.update("UPDATE restaurants SET deleted_at=CURRENT_TIMESTAMP,delete_reason_code='SYSTEM' WHERE id=1");
        mvc.perform(get("/api/reviews/"+review+"/photos/0")).andExpect(status().isNotFound());
    }
    /** 删评先提交待清理状态；OSS失败不复活点评，后台重试成功才移除文件登记。 */
    @Test void reviewDeletionQueuesBoundPhotosAndRetriesCloudFailure() throws Exception {
        String file=uploadId();
        String response=publish(reviewInput(List.of(file),UUID.randomUUID().toString()))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String review=json.readTree(response).get("data").get("reviewId").asString();
        mvc.perform(delete("/api/reviews/"+review).header("Authorization","Bearer "+other)).andExpect(status().isForbidden());
        assertThat(files.find(Long.parseLong(file)).getStatus()).isEqualTo("BOUND");
        mvc.perform(delete("/api/reviews/"+review).header("Authorization","Bearer "+owner)).andExpect(status().isNoContent());
        verify(storage,never()).delete(anyString(),anyString());
        assertThat(files.find(Long.parseLong(file)).getStatus()).isEqualTo("PENDING_DELETE");
        doThrow(new IllegalStateException("test cloud unavailable")).when(storage).delete(anyString(),anyString());
        cleanupJob().cleanup();
        assertThat(files.find(Long.parseLong(file)).getStatus()).isEqualTo("PENDING_DELETE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reviews",Long.class)).isZero();
        reset(storage);
        doAnswer(call->{
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return null;
        }).when(storage).delete(anyString(),anyString());
        cleanupJob().cleanup();
        verify(storage).delete(anyString(),anyString());
        assertThat(files.find(Long.parseLong(file))).isNull();
    }

    @Test void invalidSecondPhotoRollsBackFirstBindingReviewAndStatistics() throws Exception {
        String first=uploadId(),second=uploadId();
        jdbc.update("UPDATE media_files SET uploader_user_id=22 WHERE id=?",second);
        publish(reviewInput(List.of(first,second),UUID.randomUUID().toString())).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reviews",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM review_photos",Long.class)).isZero();
        assertThat(files.find(Long.parseLong(first)).getStatus()).isEqualTo("TEMPORARY");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM restaurant_review_statistics",Long.class)).isZero();
    }
    @Test void immediateDeleteDoesNotHoldDatabaseTransactionAndUnknownPositionIsHidden() throws Exception {
        String id=uploadId();
        doAnswer(call->{
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(files.find(Long.parseLong(id)).getStatus()).isEqualTo("PENDING_DELETE");
            return null;
        }).when(storage).delete(anyString(),anyString());
        mvc.perform(delete("/api/files/images/"+id).header("Authorization","Bearer "+owner)).andExpect(status().isNoContent());
        assertThat(files.find(Long.parseLong(id))).isNull();
        mvc.perform(get("/api/reviews/1/photos/2")).andExpect(status().isNotFound());
    }
    @Test void uploadingRecoveryCannotReactivatePendingDeletion() throws Exception {
        String id=uploadId();
        jdbc.update("UPDATE media_files SET status='UPLOADING',expires_at=CURRENT_TIMESTAMP-INTERVAL 1 SECOND WHERE id=?",id);
        cleanupJob().cleanup();
        assertThat(files.find(Long.parseLong(id)).getStatus()).isEqualTo("PENDING_DELETE");
        assertThat(files.activate(Long.parseLong(id),86400)).isZero();
        jdbc.update("UPDATE media_files SET expires_at=CURRENT_TIMESTAMP-INTERVAL 1 SECOND WHERE id=?",id);
        cleanupJob().cleanup();assertThat(files.find(Long.parseLong(id))).isNull();
    }
    @Test void expiredDuplicateAndExcessPhotoIdsAreRejected() throws Exception {
        String id=uploadId();
        publish(reviewInput(List.of(id,id),UUID.randomUUID().toString())).andExpect(status().isBadRequest());
        publish(reviewInput(List.of(id,"2","3","4","5","6"),UUID.randomUUID().toString())).andExpect(status().isBadRequest());
        jdbc.update("UPDATE media_files SET expires_at=CURRENT_TIMESTAMP-INTERVAL 1 SECOND WHERE id=?",id);
        publish(reviewInput(List.of(id),UUID.randomUUID().toString())).andExpect(status().isConflict());
    }
    @Test void concurrentPublishAndCancelCannotLeaveBoundMissingPhoto() throws Exception {
        String id=uploadId();var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var posting=executor.submit(()->{start.await();return publish(reviewInput(List.of(id),UUID.randomUUID().toString())).andReturn().getResponse().getStatus();});
            var cancelling=executor.submit(()->{start.await();return mvc.perform(delete("/api/files/images/"+id).header("Authorization","Bearer "+owner)).andReturn().getResponse().getStatus();});
            start.countDown();int postStatus=posting.get(15,TimeUnit.SECONDS),cancelStatus=cancelling.get(15,TimeUnit.SECONDS);
            if(postStatus==201) {
                assertThat(cancelStatus).isEqualTo(409);
                assertThat(files.find(Long.parseLong(id)).getStatus()).isEqualTo("BOUND");
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM review_photos",Long.class)).isEqualTo(1);
            } else {
                assertThat(postStatus).isEqualTo(409);assertThat(cancelStatus).isIn(204,202);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reviews",Long.class)).isZero();
            }
        }
    }
    private String issueToken(long user,boolean expired) {
        return createToken(user,expired);
    }
    /** 五张均可绑定与读取，第六张在业务校验阶段拒绝；保留请求的照片顺序。 */
    @Test void fivePhotosBindAndFifthPositionIsPubliclyReadable() throws Exception {
        var ids=new ArrayList<String>();
        for(int i=0;i<5;i++)ids.add(uploadId());
        Collections.reverse(ids);
        var response=publish(reviewInput(ids,UUID.randomUUID().toString())).andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        var review=json.readTree(response).get("data").get("reviewId").asString();
        assertThat(jdbc.queryForList("SELECT media_file_id FROM review_photos ORDER BY position",Long.class))
            .containsExactlyElementsOf(ids.stream().map(Long::parseLong).toList());
        when(storage.read(anyString(),anyString())).thenReturn(JpegImageProcessorTest.jpeg(5,5));
        mvc.perform(get("/api/reviews/"+review+"/photos/4")).andExpect(status().isOk());
        mvc.perform(get("/api/reviews/"+review+"/photos/5")).andExpect(status().isNotFound());
        var six=new ArrayList<>(ids);six.add("99999999");
        publish(reviewInput(six,UUID.randomUUID().toString())).andExpect(status().isBadRequest());
    }
    private String createToken(long user,boolean expired) {
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
