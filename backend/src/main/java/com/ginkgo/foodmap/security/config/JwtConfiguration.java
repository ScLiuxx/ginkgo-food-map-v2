package com.ginkgo.foodmap.security.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;

import java.io.IOException;
import java.security.interfaces.RSAPrivateCrtKey;
import java.time.Duration;
import java.util.Objects;

/** 显式加载本机 RSA 密钥；生产与开发均不自动生成临时密钥，重启后现有会话保持可验证。 */
@Configuration
public class JwtConfiguration {
    /** PKCS#8 私钥和 X.509 公钥必须匹配且至少 2048 位；测试通过内存密钥 Bean 替换。 */
    @Bean
    @ConditionalOnMissingBean(RSAKey.class)
    RSAKey jwtRsaKey(AuthenticationProperties properties, ResourceLoader loader)
            throws IOException {
        var config = properties.getJwt();
        try (var privateInput =
                        loader.getResource(config.getPrivateKeyLocation()).getInputStream();
                var publicInput =
                        loader.getResource(config.getPublicKeyLocation()).getInputStream()) {
            var privateKey = Objects.requireNonNull(RsaKeyConverters.pkcs8().convert(privateInput));
            var publicKey = Objects.requireNonNull(RsaKeyConverters.x509().convert(publicInput));
            if (!(privateKey instanceof RSAPrivateCrtKey crt)
                    || publicKey.getModulus().bitLength() < 2048
                    || !publicKey.getModulus().equals(crt.getModulus())
                    || !publicKey.getPublicExponent().equals(crt.getPublicExponent())) {
                throw new IllegalStateException(
                        "JWT RSA keys must match and contain at least 2048 bits");
            }
            return new RSAKey.Builder(publicKey).privateKey(privateKey).build();
        }
    }

    /** 签发固定为 RS256，不允许客户端选择签名算法。 */
    @Bean
    JwtEncoder jwtEncoder(RSAKey key) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
    }

    /** 验证签名、有效期、发行方、受众和必需声明；业务会话由认证转换器继续验证。 */
    @Bean
    JwtDecoder jwtDecoder(RSAKey key, AuthenticationProperties properties) throws Exception {
        var config = properties.getJwt();
        NimbusJwtDecoder decoder =
                NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey())
                        .signatureAlgorithm(SignatureAlgorithm.RS256)
                        .build();
        decoder.setJwtValidator(
                new DelegatingOAuth2TokenValidator<>(
                        new JwtTimestampValidator(Duration.ZERO),
                        new JwtIssuerValidator(config.getIssuer()),
                        jwt -> {
                            Object version = jwt.getClaims().get("credential_version");
                            boolean valid =
                                    jwt.getAudience().contains(config.getAudience())
                                            && jwt.getExpiresAt() != null
                                            && jwt.getIssuedAt() != null
                                            && jwt.getSubject() != null
                                            && jwt.getSubject().matches("[1-9][0-9]{0,18}")
                                            && jwt.getId() != null
                                            && jwt.getId().matches("[a-f0-9-]{36}")
                                            && version instanceof Long number
                                            && number >= 0;
                            return valid
                                    ? OAuth2TokenValidatorResult.success()
                                    : OAuth2TokenValidatorResult.failure(
                                            new OAuth2Error("invalid_token", "登录凭证无效", null));
                        }));
        return decoder;
    }
}
