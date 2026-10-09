package com.victhor.delivery.payment.infrastructure.auth;

import java.net.URI;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.web.client.RestTemplate;

/** Validates RS256 access tokens signed by the User service; this service never holds a signing key. */
public final class AccessTokenVerifier {

    public static final String ISSUER = "https://delivery-order-system.local";
    public static final String AUDIENCE = "delivery-order-system";
    public static final Duration MAX_LIFETIME = Duration.ofMinutes(15);
    public static final Duration CLOCK_SKEW = Duration.ofSeconds(30);
    public static final Duration KEY_FETCH_TIMEOUT = Duration.ofSeconds(2);
    public static final String ROLES_CLAIM = "roles";
    public static final Set<String> ROLES = Set.of("CUSTOMER", "OPERATOR");

    private AccessTokenVerifier() {
    }

    /** Fetches the User service's public keys on demand; Nimbus caches them and refetches for an unknown key id. */
    public static JwtDecoder fromJwkSetUri(String jwkSetUri, Clock clock) {
        var uri = jwkSetUri == null || jwkSetUri.isBlank() ? null : URI.create(jwkSetUri.strip());
        if (uri == null || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null) {
            throw new IllegalArgumentException("USER_AUTH_JWK_SET_URI must be an absolute HTTP(S) URL");
        }
        var requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(KEY_FETCH_TIMEOUT);
        requests.setReadTimeout(KEY_FETCH_TIMEOUT);
        return withValidators(NimbusJwtDecoder.withJwkSetUri(uri.toString()).jwsAlgorithm(SignatureAlgorithm.RS256)
                .restOperations(new RestTemplate(requests)).build(), clock);
    }

    /** Verifies with a pinned Base64 X.509 public key instead of fetching the key set. */
    public static JwtDecoder fromPublicKey(String encodedPublicKey, Clock clock) {
        return withValidators(NimbusJwtDecoder.withPublicKey(publicKey(encodedPublicKey))
                .signatureAlgorithm(SignatureAlgorithm.RS256).build(), clock);
    }

    /** Maps the roles claim to ROLE_CUSTOMER / ROLE_OPERATOR authorities. */
    public static JwtAuthenticationConverter authenticationConverter() {
        var authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(ROLES_CLAIM);
        authorities.setAuthorityPrefix("ROLE_");
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    private static JwtDecoder withValidators(NimbusJwtDecoder decoder, Clock clock) {
        var timestamps = new JwtTimestampValidator(CLOCK_SKEW);
        timestamps.setClock(clock);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(timestamps, new JwtIssuerValidator(ISSUER),
                jwt -> validateClaims(jwt, clock)));
        return decoder;
    }

    private static OAuth2TokenValidatorResult validateClaims(Jwt jwt, Clock clock) {
        try {
            boolean valid = jwt.getAudience() != null && jwt.getAudience().contains(AUDIENCE)
                    && UUID.fromString(jwt.getSubject()).toString().equals(jwt.getSubject())
                    && jwt.getIssuedAt() != null && jwt.getExpiresAt() != null
                    && jwt.getExpiresAt().isAfter(jwt.getIssuedAt())
                    && !jwt.getExpiresAt().isAfter(jwt.getIssuedAt().plus(MAX_LIFETIME))
                    && !jwt.getIssuedAt().isAfter(clock.instant().plus(CLOCK_SKEW))
                    && jwt.getClaims().get(ROLES_CLAIM) instanceof List<?> roles && !roles.isEmpty()
                    && roles.stream().allMatch(ROLES::contains);
            if (valid) {
                return OAuth2TokenValidatorResult.success();
            }
        } catch (IllegalArgumentException | NullPointerException exception) {
            // A signed token still needs a usable subject, the required time claims and known roles.
        }
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid access token", null));
    }

    private static RSAPublicKey publicKey(String encodedPublicKey) {
        try {
            var key = (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(encodedPublicKey.strip())));
            if (key.getModulus().bitLength() >= 2048) {
                return key;
            }
        } catch (GeneralSecurityException | ClassCastException | IllegalArgumentException | NullPointerException exception) {
            // Reported below without echoing the configured value.
        }
        throw new IllegalArgumentException("USER_AUTH_PUBLIC_KEY must be a Base64 X.509 RSA public key of at least 2048 bits");
    }
}
