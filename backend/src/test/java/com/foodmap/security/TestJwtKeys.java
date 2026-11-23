package com.foodmap.security;

import com.nimbusds.jose.jwk.RSAKey;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

/** 测试每次运行生成内存密钥，不提交测试私钥，不读取开发或生产密钥。 */
@TestConfiguration(proxyBeanMethods = false)
public class TestJwtKeys {
    /** 临时2048位密钥只用于测试签发、签名验证和应用上下文启动。 */
    @Bean
    RSAKey testRsaKey() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var pair = generator.generateKeyPair();
        return new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                .privateKey((RSAPrivateKey) pair.getPrivate()).build();
    }
}
