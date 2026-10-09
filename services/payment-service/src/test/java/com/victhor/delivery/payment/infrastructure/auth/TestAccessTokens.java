package com.victhor.delivery.payment.infrastructure.auth;

import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/** Issues tokens shaped like the User service's, signed with public test-only RSA keys. */
public final class TestAccessTokens {

    /** Pairs with payment.auth.public-key in application-test.properties. */
    public static final String PRIVATE_KEY = "MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQDDICQ97JmF/peXdPQjYHh6fL9tJB/31t15SYAFt5kbxpke0U09Qf5pCYYpdRBzLx3SEsDyUBc0CgjlbQfN36tvAgPR6tHXsrbRrBKAR/EE5SQVhCibyHHRxkbtM5p2NVUDvIUhE50ILXTeNa5Sosr0EY5N3wiRX17kd7g50REnj1scG3RWeLzdWcWal6dnQGzKQfWK+VeLnM7e72db5Iv4xPvExNbc92Xx8uoQH8EJFMlEndYL+zJ6cDvOwpYQpDUTmKzQgx/0ZvtzFn+WJfoMCRSAcaqo0BtSiQy5kdeQbIxJbZ1B5KomTCVLBYvzKsSuefE70DqB3kgRB3XqQK3FAgMBAAECggEASGorSRf/uezMUZdD68UnsT2OxXB8tLv3IcYXTwfeOKKxvPAsXCmbw1uXgNdDLZw00vvGw6bZlaSLvKmEFfGGbAIxbLxa+FQI6TOVAiqw1gI8LAgKEhaHtzSAqhNWpbwROvvB5I6k3p4QG0+MzHpCG+ZQC7JUOa9NRjjwE/T1CtrREWSDYS+4utDf2nwBw55dPIODVn4Bb/Q5lJXwfiUqr1VaAIP0uKPt+OkTb7+vogtO0BRwUYiUFF6qH8x5n6ryQL+8h6k8EM4zSG+7iX7qOpEP2xx2tEM4DoCsoSLQBd46F9hOz+I0Xcr0RLOo5FK3zcWUtfo9X8YcYM6GNA9s2QKBgQDn/Cj50GIaXx3UakGuXU3mK1F0y8vEWLHlD69tj61Iejnw3x4+MGjs415TieRtViu5WfhAAsscdzZh8v/7ssWn0rIR7N2iJzcVbg03NUBogG9V+zu5QGflcjOyAM5DmUwfIclSVmY5F0Se+GCnoxTzZxpnLSUs6Ix+GJ+7EZaP8wKBgQDXUyseUvG2XAfViOCchirZXNpRG8mwcGPK4JRxEjwYvBMUmlolxZ8N9lGZscDRNQmDfhL7wQ7Zxqea7rMdmLNbLTfj4Qni0wlVE9hsgo1pBx0Y7qFYvTNut3dGydz5ZwfW3yjHtSadWY6GArxTZgugpWSbg11G4NW2nWnolXPxZwKBgAwjSG2RtqBUm7XyfU9HOH0zhQaMZzP1xBENGgusec+R/ZgZxHZSiBrk5pmOXHJQEOeYyoFm5AOwRRso4LXlq6vVPVXsw8fpL1uir3RVB/KvzaN9CqntscHykLveiOxGiBIU7XiuZpjUG9YJft0mjkESAvKlDQ3lePxcA3eBOxR7AoGBAJhRZPcWhZYSlBghs4IGBtmsLWOi3JHLb0xcgaVa2NhGctjoN0zw9wrRa/flHhjgA9LYpGUitHapaHbY1CvjkTy2SAsTbgLedoOQflCEKbRaK+MvK0Oy02dGsUGKGp7ym0EMq7RaGO7GI2P5G2+DiEYjuX+o9ZrmAUglIn48+r+RAoGAC02G2gFBO2Bcz807tBtmK/WKsjqy6MACg13bR0jSywB1qL+bDm6spzy5Zyx3rbN+LiMoyZe2KwKWEmi+j616aV1OftKsXhbr9JOaGezYJEg6AdM+5mIWjwCj81IryUnEu0+h/hPiq+XBiaCuw+2l6lmg6fL1gCelXHcPDjK9Rw0=";
    public static final String PUBLIC_KEY = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAwyAkPeyZhf6Xl3T0I2B4eny/bSQf99bdeUmABbeZG8aZHtFNPUH+aQmGKXUQcy8d0hLA8lAXNAoI5W0Hzd+rbwID0erR17K20awSgEfxBOUkFYQom8hx0cZG7TOadjVVA7yFIROdCC103jWuUqLK9BGOTd8IkV9e5He4OdERJ49bHBt0Vni83VnFmpenZ0BsykH1ivlXi5zO3u9nW+SL+MT7xMTW3Pdl8fLqEB/BCRTJRJ3WC/syenA7zsKWEKQ1E5is0IMf9Gb7cxZ/liX6DAkUgHGqqNAbUokMuZHXkGyMSW2dQeSqJkwlSwWL8yrErnnxO9A6gd5IEQd16kCtxQIDAQAB";
    /** A valid key that this service does not trust. */
    public static final String FOREIGN_PRIVATE_KEY = "MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQDN2ro2Hwu1kjQ/DHWcuZKPMrzjBYbxtnqhLqsSTc71wUSS4+3LJsXjVRiXe1RZZnV8WAee3m/OcMbabCGVM4KVLSK4T7lHJFiexWxJdchtleI7LVhgTrAyAp93y6I3xYMf2CnC7IYwvvTGD7Ih5wt9a359ldhY3xaCmeNG5UKvoD2DHmCo14Yjx4N7dp0NiUgceE4z7t3nPi1ZfofeOFX4ZW9xk4CEwgAhxkqtrxSm0zlqZeOMFrvxjjENrMFLF2nLr+9OLinHNdRf+rdG7DE4rR5Qwb+w86kPiXQSDBDs1xKplCcbXO4wCyRCmDM63xmvNIl8eS9hK0L35f+6yQ8FAgMBAAECggEACgFqCx7jrrnc6b1FNdlW5w3AMhL5nePbc9xPlYEW1d7vYmoLNmmw9SLUoj+XmDfQw+WIdfTu/3GqV1appDWf0M3j5zb9dQMufoXEhLvhl81Q9MTjC91PQZhbNWio/r0jftz+x09qmfxyFnK6sFypSY7maUbAVa4piy6ye2aXIO+abz3ET7d71b8gPVC8mEVdbW/f719bFcQfUhWNhoUUcjddUBj3O0mOk9/xiEzlcmFhOD6Lzd2mGdKgF1taFD1xdmqE2D70a6kmI3z4jx1ilLRY3Cc+bE3+qcuVw00z9NWRtyYcqrTAA62tIRjY2IP1RQRrn9CIfuS2x8WlNQQjgQKBgQD8/N6Hj/+Rf5IPVE5beaorgnp50OiTT4qmrgrLt7mfaLZeVqxOYgdfVC/vPrivnn2vA3DCn7AG9+AkuTGQsuY5uCfnF4Mq6ZquhgdWX+OTItrm9DbvnWEA1rfrBgv6I7IYKqne03PaWo1esWjTtNJEXKDRS0yYevCQT92n716SgQKBgQDQTjDvlLDoKNKtkpomKKH4dZaOTsMmFv3PnL+gGMHco0SsRnkDmL5nHnX71OLRql3PcLGoQP9FhCxFMMh3lYE5Fq/u3Q48CQFDOkR9tq2Bs7n+eb2GQ0pNo6b05psijLhpwIO8hnSaNJN3S5ON5IJ3cYC1TNPd8Tq0OYIXwanyhQKBgARAB0kFvUhneT+yreJRh+9VMNONE+stoems8Nd9TawE6VNqZ1ilwvPyCSAe0KF6qyfeie1rG1zymxxi1BdXOhDsLBdwyK5W4FdgPw1PbRZStpS8s5OQ9Ek8UjirkFXydZJ8XQA2UzLu5IMbveQYnJOzbqw1dKL3JcV24gVpOxCBAoGBAI9q5mJtm4ecY8FByIQxQaNRQoFkVRQqjEGfCIhvwznn52Y9dyA00BMrc8wZfLkidUhXrNnNNnRkVh6lQcCj3L6zkhoBdMV85bOlsHZlifxdA9fjdcu1FLlzzYWcKH+XJ3kYJRtt72YkgMuH62WsSO935EfvR4ftiJ+BYMJ+gkYJAoGBAI6HuUEeZogKVsLP/JwTxjsrx6dHP6//SxIKxfCx636ykpPGjoAZBct0ZmbKVApzALKqU0SB8PoQeHhTlJlchYmU14KM1T/nNqv4qtrPFBgN4Nb59nplhFpwkkQLkf/1wWLSjVSo/3Kj28wkbZZC2XxViLqoACRwSVCNxkeZPE6C";

    private TestAccessTokens() {
    }

    public static String issue(UUID userId) {
        return issue(PRIVATE_KEY, Instant.now(), claims -> claims.subject(userId.toString()));
    }

    public static String issueOperator(UUID userId) {
        return issue(PRIVATE_KEY, Instant.now(), claims -> claims.subject(userId.toString())
                .claim(AccessTokenVerifier.ROLES_CLAIM, List.of("OPERATOR")));
    }

    public static String issue(String privateKey, Instant issuedAt, Consumer<JwtClaimsSet.Builder> customizer) {
        var claims = JwtClaimsSet.builder().issuer(AccessTokenVerifier.ISSUER).subject(UUID.randomUUID().toString())
                .audience(List.of(AccessTokenVerifier.AUDIENCE)).issuedAt(issuedAt).notBefore(issuedAt)
                .expiresAt(issuedAt.plus(Duration.ofMinutes(15))).claim(AccessTokenVerifier.ROLES_CLAIM, List.of("CUSTOMER"))
                .id(UUID.randomUUID().toString());
        customizer.accept(claims);
        var key = rsaKey(privateKey);
        var header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(key.getKeyID()).type("JWT").build();
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)))
                .encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
    }

    public static RSAKey rsaKey(String privateKey) {
        try {
            var keys = KeyFactory.getInstance("RSA");
            var priv = (RSAPrivateCrtKey) keys.generatePrivate(
                    new PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKey)));
            var pub = (RSAPublicKey) keys.generatePublic(new RSAPublicKeySpec(priv.getModulus(), priv.getPublicExponent()));
            return new RSAKey.Builder(pub).privateKey(priv).keyIDFromThumbprint().build();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
