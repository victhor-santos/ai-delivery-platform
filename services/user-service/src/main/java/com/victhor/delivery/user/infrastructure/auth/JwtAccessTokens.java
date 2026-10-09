package com.victhor.delivery.user.infrastructure.auth;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import com.victhor.delivery.user.application.AccessToken;
import com.victhor.delivery.user.application.AccessTokenIssuer;
import com.victhor.delivery.user.domain.Role;

/** Signs access tokens with the User service's RSA private key; other services verify them with the public JWKS. */
public class JwtAccessTokens implements AccessTokenIssuer {

    public static final String ISSUER = "https://delivery-order-system.local";
    public static final String AUDIENCE = "delivery-order-system";
    public static final Duration LIFETIME = Duration.ofMinutes(15);
    public static final Duration CLOCK_SKEW = Duration.ofSeconds(30);
    public static final String ROLES_CLAIM = "roles";
    public static final int MIN_KEY_BITS = 2048;
    static final String INVALID_KEY = "USER_AUTH_PRIVATE_KEY must be a Base64 PKCS#8 RSA private key of at least 2048 bits";

    private final Clock clock;
    private final RSAKey signingKey;
    private final JwtEncoder encoder;
    private final JwtDecoder decoder;

    public JwtAccessTokens(String encodedPrivateKey, Clock clock) {
        this.clock = clock;
        signingKey = signingKey(encodedPrivateKey);
        encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(signingKey)));
        NimbusJwtDecoder nimbus;
        try {
            nimbus = NimbusJwtDecoder.withPublicKey(signingKey.toRSAPublicKey())
                    .signatureAlgorithm(SignatureAlgorithm.RS256).build();
        } catch (JOSEException exception) {
            throw new IllegalArgumentException(INVALID_KEY);
        }
        var timestamps = new JwtTimestampValidator(CLOCK_SKEW);
        timestamps.setClock(clock);
        nimbus.setJwtValidator(new DelegatingOAuth2TokenValidator<>(timestamps,
                new JwtIssuerValidator(ISSUER), this::validateClaims));
        decoder = nimbus;
    }

    @Override
    public AccessToken issue(UUID userId, Role role) {
        var now = clock.instant();
        var expiresAt = now.plus(LIFETIME);
        var claims = JwtClaimsSet.builder().issuer(ISSUER).subject(userId.toString())
                .audience(List.of(AUDIENCE)).issuedAt(now).notBefore(now).expiresAt(expiresAt)
                .claim(ROLES_CLAIM, List.of(role.name())).id(UUID.randomUUID().toString()).build();
        var header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(signingKey.getKeyID()).type("JWT").build();
        String value = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new AccessToken(value, expiresAt, LIFETIME.toSeconds());
    }

    public JwtDecoder decoder() {
        return decoder;
    }

    /** The public half only, as served to the services that verify tokens. */
    public Map<String, Object> publicKeys() {
        return new JWKSet(signingKey.toPublicJWK()).toJSONObject();
    }

    private OAuth2TokenValidatorResult validateClaims(Jwt jwt) {
        try {
            boolean valid = jwt.getAudience() != null && jwt.getAudience().contains(AUDIENCE)
                    && UUID.fromString(jwt.getSubject()).toString().equals(jwt.getSubject())
                    && jwt.getIssuedAt() != null && jwt.getExpiresAt() != null
                    && jwt.getExpiresAt().isAfter(jwt.getIssuedAt())
                    && !jwt.getExpiresAt().isAfter(jwt.getIssuedAt().plus(LIFETIME))
                    && !jwt.getIssuedAt().isAfter(clock.instant().plus(CLOCK_SKEW))
                    && hasKnownRoles(jwt);
            if (valid) {
                return OAuth2TokenValidatorResult.success();
            }
        } catch (IllegalArgumentException | NullPointerException exception) {
            // A signed token still needs a usable subject and the required time claims.
        }
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid access token", null));
    }

    private static boolean hasKnownRoles(Jwt jwt) {
        return jwt.getClaims().get(ROLES_CLAIM) instanceof List<?> roles && !roles.isEmpty()
                && roles.stream().allMatch(JwtAccessTokens::isRole);
    }

    private static boolean isRole(Object value) {
        for (Role role : Role.values()) {
            if (role.name().equals(value)) {
                return true;
            }
        }
        return false;
    }

    private static RSAKey signingKey(String encodedPrivateKey) {
        try {
            var keys = KeyFactory.getInstance("RSA");
            var privateKey = (RSAPrivateCrtKey) keys.generatePrivate(
                    new PKCS8EncodedKeySpec(Base64.getDecoder().decode(encodedPrivateKey.strip())));
            BigInteger modulus = privateKey.getModulus();
            if (modulus.bitLength() < MIN_KEY_BITS) {
                throw new IllegalArgumentException(INVALID_KEY);
            }
            var publicKey = (RSAPublicKey) keys.generatePublic(
                    new RSAPublicKeySpec(modulus, privateKey.getPublicExponent()));
            return new RSAKey.Builder(publicKey).privateKey(privateKey).keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256).keyIDFromThumbprint().build();
        } catch (GeneralSecurityException | JOSEException | ClassCastException | IllegalArgumentException
                | NullPointerException exception) {
            // The message never echoes the configured value.
            throw new IllegalArgumentException(INVALID_KEY);
        }
    }
}
