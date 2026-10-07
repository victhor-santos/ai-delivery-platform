package com.victhor.delivery.user.api;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.victhor.delivery.user.application.AuthenticationService;
import com.victhor.delivery.user.application.UserAddressPage;
import com.victhor.delivery.user.application.UserAddressService;
import com.victhor.delivery.user.application.UserProfileService;
import com.victhor.delivery.user.domain.EmailAddress;
import com.victhor.delivery.user.domain.UserAddress;
import com.victhor.delivery.user.domain.UserProfile;
import com.victhor.delivery.user.infrastructure.auth.JwtAccessTokens;

/** Exercises the HTTP authorization policy with the real token decoder and mocked use cases; no database. */
@WebMvcTest(controllers = { UserProfileController.class, UserAddressController.class,
        AuthenticationController.class, UserPingController.class })
@Import({ SecurityConfiguration.class, JsonConfiguration.class, UserAuthorizationTests.Tokens.class })
@ActiveProfiles("test")
class UserAuthorizationTests {

    private static final UUID OWNER = UUID.fromString("5c0f9a4e-2a4f-4c5b-9d7e-0e6b1a2c3d4e");
    private static final UUID OTHER = UUID.fromString("8d1e2f3a-4b5c-4d6e-8f70-112233445566");
    private static final UUID ADDRESS = UUID.fromString("0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d");
    private static final String ADDRESS_BODY = """
            {"label":"Casa","address":"Rua A, 1","latitude":0,"longitude":0}
            """;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JwtAccessTokens tokens;
    @Value("${user.auth.secret}")
    private String secret;

    @MockitoBean
    private UserProfileService users;
    @MockitoBean
    private UserAddressService addresses;
    @MockitoBean
    private AuthenticationService authentication;

    @TestConfiguration(proxyBeanMethods = false)
    static class Tokens {

        @Bean
        JwtAccessTokens accessTokens(@Value("${user.auth.secret}") String secret) {
            return new JwtAccessTokens(secret, Clock.systemUTC());
        }

        @Bean
        JwtDecoder jwtDecoder(JwtAccessTokens tokens) {
            return tokens.decoder();
        }
    }

    record Call(String method, String path, String body) {

        MockHttpServletRequestBuilder request() {
            var request = switch (method) {
                case "GET" -> get(path);
                case "POST" -> post(path);
                default -> put(path);
            };
            return body == null ? request : json(request, body);
        }

        @Override
        public String toString() {
            return method + " " + path;
        }
    }

    static Stream<Call> foreignResourceRequests() {
        String profile = "/api/users/" + OWNER;
        String addressList = profile + "/addresses";
        return Stream.of(new Call("GET", profile, null), new Call("PUT", profile + "/profile", "{\"name\":\"Nome\"}"),
                new Call("POST", addressList, ADDRESS_BODY), new Call("GET", addressList, null),
                new Call("GET", addressList + "/" + ADDRESS, null),
                new Call("PUT", addressList + "/" + ADDRESS, ADDRESS_BODY));
    }

    static Stream<Call> protectedRequests() {
        return Stream.concat(foreignResourceRequests(), Stream.of(new Call("GET", "/api/users/auth/me", null),
                new Call("POST", "/api/users", "{\"name\":\"Cliente\",\"email\":\"a@example.test\"}")));
    }

    @ParameterizedTest
    @MethodSource("protectedRequests")
    void rejectsMissingTokensBeforeReachingTheUseCases(Call call) throws Exception {
        assertUnauthorized(call.request());
        verifyNoInteractions(users, addresses, authentication);
    }

    @ParameterizedTest
    @MethodSource("protectedRequests")
    void rejectsMalformedExpiredAndForeignSignedTokens(Call call) throws Exception {
        String expired = new JwtAccessTokens(secret, Clock.fixed(Instant.now().minusSeconds(1000), ZoneOffset.UTC))
                .issue(OWNER).value();
        String foreign = new JwtAccessTokens("ZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmY=", Clock.systemUTC())
                .issue(OWNER).value();
        for (String token : List.of("not-a-token", expired, foreign)) {
            assertUnauthorized(call.request().header("Authorization", "Bearer " + token));
        }
        verifyNoInteractions(users, addresses, authentication);
    }

    @ParameterizedTest
    @MethodSource("foreignResourceRequests")
    void reportsAnotherUsersProfileAndAddressesAsAbsentWithoutCallingTheUseCases(
            Call call) throws Exception {
        mvc.perform(call.request().header("Authorization", bearer(OTHER)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Usuário não encontrado."));
        verifyNoInteractions(users, addresses);
    }

    @Test
    void letsTheOwnerReadAndChangeTheirProfileAndAddresses() throws Exception {
        var profile = new UserProfile(OWNER, "Cliente", new EmailAddress("cliente@example.test"));
        var address = new UserAddress(ADDRESS, OWNER, "Casa", "Rua A, 1", 0, 0);
        given(users.findById(OWNER)).willReturn(profile);
        given(users.updateName(OWNER, "Nome")).willReturn(profile);
        given(addresses.create(eq(OWNER), anyString(), anyString(), eq(0.0), eq(0.0))).willReturn(address);
        given(addresses.findById(OWNER, ADDRESS)).willReturn(address);
        given(addresses.update(eq(OWNER), eq(ADDRESS), anyString(), anyString(), eq(0.0), eq(0.0)))
                .willReturn(address);
        given(addresses.findAll(eq(OWNER), anyInt(), anyInt())).willReturn(new UserAddressPage(List.of(address), 0, 20, 1));
        String token = bearer(OWNER);
        String path = "/api/users/" + OWNER;

        mvc.perform(get(path).header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(OWNER.toString()));
        mvc.perform(json(put(path + "/profile"), "{\"name\":\"Nome\"}").header("Authorization", token))
                .andExpect(status().isOk());
        mvc.perform(json(post(path + "/addresses"), ADDRESS_BODY).header("Authorization", token))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", path + "/addresses/" + ADDRESS));
        mvc.perform(get(path + "/addresses").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].userId").value(OWNER.toString()));
        mvc.perform(get(path + "/addresses/" + ADDRESS).header("Authorization", token))
                .andExpect(status().isOk());
        mvc.perform(json(put(path + "/addresses/" + ADDRESS), ADDRESS_BODY).header("Authorization", token))
                .andExpect(status().isOk());
        mvc.perform(get("/api/users/auth/me").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(OWNER.toString()));
        verify(addresses).findAll(OWNER, 0, 20);
    }

    @Test
    void keepsRegistrationLoginPingAndHealthPublicAndNoLongerCreatesPasswordlessProfiles() throws Exception {
        given(authentication.register("Cliente", "a@example.test", "correct-password-123"))
                .willReturn(new UserProfile(OWNER, "Cliente", new EmailAddress("a@example.test")));
        mvc.perform(json(post("/api/users/auth/register"),
                "{\"name\":\"Cliente\",\"email\":\"a@example.test\",\"password\":\"correct-password-123\"}"))
                .andExpect(status().isCreated());
        mvc.perform(json(post("/api/users/auth/login"), "{}")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/users/ping")).andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("user-service"));
        mvc.perform(json(post("/api/users"), "{\"name\":\"Cliente\",\"email\":\"b@example.test\"}")
                .header("Authorization", bearer(OWNER)))
                .andExpect(status().isNotFound());
        verify(authentication).register("Cliente", "a@example.test", "correct-password-123");
        verifyNoInteractions(users, addresses);
    }

    private void assertUnauthorized(MockHttpServletRequestBuilder request) throws Exception {
        mvc.perform(request)
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.startsWith("Bearer")))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401));
    }

    private String bearer(UUID userId) {
        return "Bearer " + tokens.issue(userId).value();
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
