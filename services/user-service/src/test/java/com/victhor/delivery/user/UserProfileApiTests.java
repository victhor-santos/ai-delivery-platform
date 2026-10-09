package com.victhor.delivery.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.victhor.delivery.user.domain.Role;
import com.victhor.delivery.user.application.UserProfileService;
import com.victhor.delivery.user.infrastructure.auth.JwtAccessTokens;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "USER_DB_PASSWORD=testcontainers-only")
@Testcontainers
@ActiveProfiles("test")
class UserProfileApiTests {

    private static final String USERS = "/api/users";
    private static final String REGISTER = USERS + "/auth/register";
    private static final String PASSWORD = "correct-password-123";
    private static final String ADDRESS_BODY = """
            {"label":"Casa","address":"Rua das Flores, 10","latitude":-23.5505,"longitude":-46.6333}
            """;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtAccessTokens tokens;

    @MockitoSpyBean
    private UserProfileService users;

    @Test
    void registersAndQueriesAUserWithGeneratedIdAndCanonicalEmail() throws Exception {
        String email = uniqueEmail();
        Response created = post(REGISTER, objectMapper.writeValueAsString(Map.of(
                "name", "  Maria Vitória  ", "email", "  " + email.toUpperCase(Locale.ROOT) + "  ",
                "password", PASSWORD)));

        assertThat(created.status()).isEqualTo(201);
        String id = created.body().path("id").asString();
        assertThat(UUID.fromString(id)).isNotNull();
        assertThat(created.headers().firstValue("Location")).contains(USERS + "/" + id);
        assertThat(created.body().path("name").asString()).isEqualTo("Maria Vitória");
        assertThat(created.body().path("email").asString()).isEqualTo(email);
        assertThat(created.body().size()).isEqualTo(3);

        Response found = get(USERS + "/" + id, token(id));
        assertThat(found.status()).isEqualTo(200);
        assertThat(found.body()).isEqualTo(created.body());
    }

    @Test
    void updatesOnlyTheNameAndKeepsTheRegisteredEmailAndIdentity() throws Exception {
        Account created = createUser();
        String path = USERS + "/" + created.id();

        Response updated = put(path + "/profile", """
                {"name":"  Nome atualizado  ","email":"ignored@example.com","id":"ignored","role":"ADMIN"}
                """, created.token());

        assertThat(updated.status()).isEqualTo(200);
        assertThat(updated.body().path("name").asString()).isEqualTo("Nome atualizado");
        assertThat(updated.body().path("email")).isEqualTo(created.profile().path("email"));
        assertThat(updated.body().path("id")).isEqualTo(created.profile().path("id"));
        assertThat(updated.body().size()).isEqualTo(3);
        assertThat(get(path, created.token()).body()).isEqualTo(updated.body());
        assertThat(put(path + "/profile", "{\"name\":\"Nome atualizado\"}", created.token()).body())
                .isEqualTo(updated.body());
    }

    @Test
    void ignoresClientSuppliedIdentityAndOtherUnrecognizedRegistrationFields() throws Exception {
        UUID suppliedId = UUID.randomUUID();
        Response created = post(REGISTER, objectMapper.writeValueAsString(Map.of(
                "name", "Cliente", "email", uniqueEmail(), "id", suppliedId,
                "password", PASSWORD, "role", "ADMIN")));

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body().path("id").asString()).isNotEqualTo(suppliedId.toString());
        assertThat(created.body().size()).isEqualTo(3);
        assertProblem(get(USERS + "/" + suppliedId, token(suppliedId.toString())), 404);
    }

    @Test
    void rejectsDuplicateEmailsAfterWhitespaceAndCaseNormalization() throws Exception {
        Account created = createUser();
        String email = created.profile().path("email").asString();
        Response duplicate = post(REGISTER, objectMapper.writeValueAsString(Map.of(
                "name", "Outro cliente", "email", " " + email.toUpperCase(Locale.ROOT) + " ",
                "password", PASSWORD)));

        assertProblem(duplicate, 409);
        assertThat(duplicate.body().toString()).doesNotContain(email, "Outro cliente");
        assertThat(get(USERS + "/" + created.id(), created.token()).body()).isEqualTo(created.profile());
    }

    @Test
    void acceptsTheMaximumNameAndEmailLengthsAfterTrimming() throws Exception {
        String email = "a".repeat(64) + "@" + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(61);
        assertThat(email).hasSize(254);

        Response response = post(REGISTER, objectMapper.writeValueAsString(Map.of(
                "name", "  " + "N".repeat(120) + "  ", "email", "  " + email + "  ", "password", PASSWORD)));

        assertThat(response.status()).isEqualTo(201);
        assertThat(response.body().path("name").asString()).hasSize(120);
        assertThat(response.body().path("email").asString()).isEqualTo(email);
    }

    @ParameterizedTest
    @MethodSource("invalidUserBodies")
    void rejectsMalformedOrInvalidProfileDataInRegistration(String body) throws Exception {
        String withPassword = body.startsWith("{\"") ? "{\"password\":\"" + PASSWORD + "\"," + body.substring(1) : body;
        assertProblem(post(REGISTER, withPassword), 400);
    }

    static Stream<String> invalidUserBodies() {
        return Stream.of("", "{", "null", "[]", "true", "{}",
                "{\"email\":\"valid@example.com\"}",
                "{\"name\":null,\"email\":\"valid@example.com\"}",
                "{\"name\":\" \",\"email\":\"valid@example.com\"}",
                "{\"name\":\"\u2003\",\"email\":\"valid@example.com\"}",
                "{\"name\":\"" + "n".repeat(121) + "\",\"email\":\"valid@example.com\"}",
                "{\"name\":123,\"email\":\"valid@example.com\"}",
                "{\"name\":1.5,\"email\":\"valid@example.com\"}",
                "{\"name\":true,\"email\":\"valid@example.com\"}",
                "{\"name\":{},\"email\":\"valid@example.com\"}",
                "{\"name\":\"Cliente\"}", "{\"name\":\"Cliente\",\"email\":null}",
                "{\"name\":\"Cliente\",\"email\":\"\"}",
                "{\"name\":\"Cliente\",\"email\":\"  \"}",
                "{\"name\":\"Cliente\",\"email\":true}",
                "{\"name\":\"Cliente\",\"email\":123}",
                "{\"name\":\"Cliente\",\"email\":1.5}",
                "{\"name\":\"Cliente\",\"email\":\"not-an-email\"}",
                "{\"name\":\"Cliente\",\"email\":\"a@@example.com\"}",
                "{\"name\":\"Cliente\",\"email\":\"a@localhost\"}",
                "{\"name\":\"Cliente\",\"email\":\".a@example.com\"}",
                "{\"name\":\"Cliente\",\"email\":\"a..b@example.com\"}",
                "{\"name\":\"Cliente\",\"email\":\"á@example.com\"}",
                "{\"name\":\"Cliente\",\"email\":\"" + "a".repeat(255) + "\"}");
    }

    @ParameterizedTest
    @MethodSource("invalidProfileBodies")
    void rejectsInvalidNameUpdatesWithoutChangingTheProfile(String body) throws Exception {
        Account created = createUser();
        String path = USERS + "/" + created.id();

        assertProblem(put(path + "/profile", body, created.token()), 400);

        assertThat(get(path, created.token()).body()).isEqualTo(created.profile());
    }

    static Stream<String> invalidProfileBodies() {
        return Stream.of("", "{", "null", "[]", "{}", "{\"name\":null}", "{\"name\":\"\"}",
                "{\"name\":\"  \"}", "{\"name\":\"\u2003\"}", "{\"name\":123}",
                "{\"name\":1.5}", "{\"name\":true}", "{\"name\":{}}",
                "{\"name\":\"" + "n".repeat(121) + "\"}");
    }

    @Test
    void createsQueriesAndReplacesAnAddressWithoutChangingOwnershipOrIdentity() throws Exception {
        Account owner = createUser();
        String path = addressPath(owner.id());
        Response created = post(path, """
                {"label":"  Casa  ","address":"  Rua das Flores, 10  ","latitude":-23.5505,"longitude":-46.6333}
                """, owner.token());

        assertThat(created.status()).isEqualTo(201);
        String id = created.body().path("id").asString();
        assertThat(UUID.fromString(id)).isNotNull();
        assertThat(created.headers().firstValue("Location")).contains(path + "/" + id);
        assertThat(created.body().path("userId").asString()).isEqualTo(owner.id());
        assertThat(created.body().path("label").asString()).isEqualTo("Casa");
        assertThat(created.body().path("address").asString()).isEqualTo("Rua das Flores, 10");
        assertThat(created.body().path("latitude").asDouble()).isEqualTo(-23.5505);
        assertThat(created.body().path("longitude").asDouble()).isEqualTo(-46.6333);
        assertThat(created.body().size()).isEqualTo(6);
        assertThat(get(path + "/" + id, owner.token()).body()).isEqualTo(created.body());

        Response updated = put(path + "/" + id, objectMapper.writeValueAsString(Map.of(
                "label", "Trabalho", "address", "Avenida Central, 20", "latitude", 0,
                "longitude", 180, "id", UUID.randomUUID(), "userId", UUID.randomUUID())), owner.token());

        assertThat(updated.status()).isEqualTo(200);
        assertThat(updated.body().path("id")).isEqualTo(created.body().path("id"));
        assertThat(updated.body().path("userId")).isEqualTo(created.body().path("userId"));
        assertThat(updated.body().path("label").asString()).isEqualTo("Trabalho");
        assertThat(updated.body().path("address").asString()).isEqualTo("Avenida Central, 20");
        assertThat(updated.body().path("latitude").asDouble()).isZero();
        assertThat(updated.body().path("longitude").asDouble()).isEqualTo(180);
        assertThat(get(path + "/" + id, owner.token()).body()).isEqualTo(updated.body());
        assertThat(get(path, owner.token()).body().path("totalElements").asLong()).isEqualTo(1);
    }

    @Test
    void generatesAddressIdentityAndKeepsTheOwnerFromThePath() throws Exception {
        Account owner = createUser();
        Account other = createUser();
        UUID suppliedId = UUID.randomUUID();
        Response created = post(addressPath(owner.id()), objectMapper.writeValueAsString(Map.of(
                "label", "Casa", "address", "Rua A, 1", "latitude", 0, "longitude", 0,
                "id", suppliedId, "userId", other.id())), owner.token());

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body().path("id").asString()).isNotEqualTo(suppliedId.toString());
        assertThat(created.body().path("userId").asString()).isEqualTo(owner.id());
        assertThat(get(addressPath(other.id()), other.token()).body().path("totalElements").asLong()).isZero();
    }

    @Test
    void acceptsTheMaximumAddressLengthsAfterTrimming() throws Exception {
        Account owner = createUser();
        Response created = post(addressPath(owner.id()), objectMapper.writeValueAsString(Map.of(
                "label", "  " + "L".repeat(80) + "  ", "address", "  " + "A".repeat(255) + "  ",
                "latitude", 0, "longitude", 0)), owner.token());

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body().path("label").asString()).hasSize(80);
        assertThat(created.body().path("address").asString()).hasSize(255);
    }

    @ParameterizedTest
    @MethodSource("boundaryCoordinates")
    void acceptsFiniteCoordinateBoundaries(double latitude, double longitude) throws Exception {
        Account owner = createUser();
        Response created = post(addressPath(owner.id()), objectMapper.writeValueAsString(Map.of(
                "label", "Local", "address", "Rua A", "latitude", latitude, "longitude", longitude)), owner.token());

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body().path("latitude").asDouble()).isEqualTo(latitude);
        assertThat(created.body().path("longitude").asDouble()).isEqualTo(longitude);
    }

    static Stream<Arguments> boundaryCoordinates() {
        return Stream.of(Arguments.of(-90, -180), Arguments.of(90, 180));
    }

    @Test
    void returnsNotFoundForAddressesOwnedByAnotherUserWithoutChangingThem() throws Exception {
        Account owner = createUser();
        Account other = createUser();
        Response created = post(addressPath(owner.id()), ADDRESS_BODY, owner.token());
        assertThat(created.status()).isEqualTo(201);
        String id = created.body().path("id").asString();

        assertProblem(get(addressPath(other.id()) + "/" + id, other.token()), 404);
        assertProblem(put(addressPath(other.id()) + "/" + id, ADDRESS_BODY, other.token()), 404);

        assertThat(get(addressPath(owner.id()) + "/" + id, owner.token()).body()).isEqualTo(created.body());
        assertThat(get(addressPath(other.id()), other.token()).body().path("items").size()).isZero();
    }

    @Test
    void hidesAnotherUsersProfileAndAddressesFromAValidTokenWithoutChangingThem() throws Exception {
        Account owner = createUser();
        Account other = createUser();
        String profile = USERS + "/" + owner.id();
        Response address = post(addressPath(owner.id()), ADDRESS_BODY, owner.token());
        String addressUri = addressPath(owner.id()) + "/" + address.body().path("id").asString();

        assertProblem(get(profile, other.token()), 404);
        assertProblem(put(profile + "/profile", "{\"name\":\"Invasor\"}", other.token()), 404);
        assertProblem(get(addressPath(owner.id()), other.token()), 404);
        assertProblem(post(addressPath(owner.id()), ADDRESS_BODY, other.token()), 404);
        assertProblem(get(addressUri, other.token()), 404);
        assertProblem(put(addressUri, "{\"label\":\"Outro\",\"address\":\"Rua B\",\"latitude\":1,\"longitude\":1}",
                other.token()), 404);

        assertThat(get(profile, owner.token()).body()).isEqualTo(owner.profile());
        assertThat(get(addressUri, owner.token()).body()).isEqualTo(address.body());
        assertThat(get(addressPath(owner.id()), owner.token()).body().path("totalElements").asLong()).isEqualTo(1);
    }

    @Test
    void requiresAValidBearerTokenForProfilesAndAddresses() throws Exception {
        Account owner = createUser();
        String profile = USERS + "/" + owner.id();

        assertUnauthorized(get(profile));
        assertUnauthorized(put(profile + "/profile", "{\"name\":\"Sem token\"}"));
        assertUnauthorized(get(addressPath(owner.id())));
        assertUnauthorized(post(addressPath(owner.id()), ADDRESS_BODY));
        assertUnauthorized(get(profile, "not-a-token"));

        assertThat(get(profile, owner.token()).body()).isEqualTo(owner.profile());
        assertThat(get(addressPath(owner.id()), owner.token()).body().path("totalElements").asLong()).isZero();
    }

    @Test
    void returnsNotFoundForUnknownUsersAndAddresses() throws Exception {
        String missingUser = UUID.randomUUID().toString();
        String missingToken = token(missingUser);
        String missingAddress = UUID.randomUUID().toString();
        Account known = createUser();

        assertProblem(get(USERS + "/" + missingUser, missingToken), 404);
        assertProblem(put(USERS + "/" + missingUser + "/profile", "{\"name\":\"Novo nome\"}", missingToken), 404);
        assertProblem(get(addressPath(missingUser), missingToken), 404);
        assertProblem(post(addressPath(missingUser), ADDRESS_BODY, missingToken), 404);
        assertProblem(get(addressPath(missingUser) + "/" + missingAddress, missingToken), 404);
        assertProblem(put(addressPath(missingUser) + "/" + missingAddress, ADDRESS_BODY, missingToken), 404);
        assertProblem(get(addressPath(known.id()) + "/" + missingAddress, known.token()), 404);
        assertProblem(put(addressPath(known.id()) + "/" + missingAddress, ADDRESS_BODY, known.token()), 404);
    }

    @ParameterizedTest
    @MethodSource("invalidAddressBodies")
    void rejectsInvalidAddressCreationAndReplacementWithoutChangingStoredData(String body) throws Exception {
        Account owner = createUser();
        String path = addressPath(owner.id());
        Response created = post(path, ADDRESS_BODY, owner.token());
        assertThat(created.status()).isEqualTo(201);
        String address = path + "/" + created.body().path("id").asString();

        assertProblem(post(path, body, owner.token()), 400);
        assertProblem(put(address, body, owner.token()), 400);

        assertThat(get(address, owner.token()).body()).isEqualTo(created.body());
        assertThat(get(path, owner.token()).body().path("totalElements").asLong()).isEqualTo(1);
    }

    static Stream<String> invalidAddressBodies() {
        String prefix = "{\"label\":\"Casa\",\"address\":\"Rua A\",";
        return Stream.of("", "{", "null", "[]", "true", "{}",
                "{\"address\":\"Rua A\",\"latitude\":0,\"longitude\":0}",
                "{\"label\":null,\"address\":\"Rua A\",\"latitude\":0,\"longitude\":0}",
                "{\"label\":\" \",\"address\":\"Rua A\",\"latitude\":0,\"longitude\":0}",
                "{\"label\":\"" + "l".repeat(81) + "\",\"address\":\"Rua A\",\"latitude\":0,\"longitude\":0}",
                "{\"label\":123,\"address\":\"Rua A\",\"latitude\":0,\"longitude\":0}",
                "{\"label\":true,\"address\":\"Rua A\",\"latitude\":0,\"longitude\":0}",
                "{\"label\":\"Casa\",\"latitude\":0,\"longitude\":0}",
                "{\"label\":\"Casa\",\"address\":null,\"latitude\":0,\"longitude\":0}",
                "{\"label\":\"Casa\",\"address\":\" \",\"latitude\":0,\"longitude\":0}",
                "{\"label\":\"Casa\",\"address\":\"" + "a".repeat(256) + "\",\"latitude\":0,\"longitude\":0}",
                "{\"label\":\"Casa\",\"address\":1.5,\"latitude\":0,\"longitude\":0}",
                prefix + "\"longitude\":0}", prefix + "\"latitude\":0}",
                prefix + "\"latitude\":null,\"longitude\":0}",
                prefix + "\"latitude\":0,\"longitude\":null}",
                prefix + "\"latitude\":\"-23.5505\",\"longitude\":0}",
                prefix + "\"latitude\":0,\"longitude\":\"\"}",
                prefix + "\"latitude\":true,\"longitude\":0}",
                prefix + "\"latitude\":0,\"longitude\":false}",
                prefix + "\"latitude\":-90.000001,\"longitude\":0}",
                prefix + "\"latitude\":90.000001,\"longitude\":0}",
                prefix + "\"latitude\":0,\"longitude\":-180.000001}",
                prefix + "\"latitude\":0,\"longitude\":180.000001}",
                prefix + "\"latitude\":1e309,\"longitude\":0}",
                prefix + "\"latitude\":0,\"longitude\":\"NaN\"}",
                prefix + "\"latitude\":\"Infinity\",\"longitude\":0}");
    }

    @Test
    void returnsAnEmptyAddressPageWithDefaultPagination() throws Exception {
        Account owner = createUser();
        Response response = get(addressPath(owner.id()), owner.token());

        assertThat(response.status()).isEqualTo(200);
        assertPage(response.body(), 0, 20, 0, 0);
        assertThat(response.body().path("items").isArray()).isTrue();
        assertThat(response.body().path("items").size()).isZero();
    }

    @Test
    void paginatesOnlyTheUsersAddressesWithStableLabelAndUuidOrdering() throws Exception {
        Account owner = createUser();
        Account other = createUser();
        String path = addressPath(owner.id());
        assertThat(post(addressPath(other.id()), ADDRESS_BODY, other.token()).status()).isEqualTo(201);
        List<JsonNode> addresses = List.of(createAddress(owner, "Zulu"), createAddress(owner, "Alpha"),
                createAddress(owner, "Bravo"), createAddress(owner, "Alpha"), createAddress(owner, "Delta"));
        List<String> expectedIds = addresses.stream()
                .sorted(Comparator.comparing((JsonNode address) -> address.path("label").asString())
                        .thenComparing(address -> address.path("id").asString()))
                .map(address -> address.path("id").asString()).toList();

        for (int page = 0; page < 3; page++) {
            Response response = get(path + "?page=" + page + "&size=2", owner.token());
            assertThat(response.status()).isEqualTo(200);
            assertPage(response.body(), page, 2, 5, 3);
            assertThat(ids(response.body())).containsExactlyElementsOf(
                    expectedIds.subList(page * 2, Math.min(page * 2 + 2, expectedIds.size())));
        }

        assertThat(ids(get(path + "?page=0&size=2", owner.token()).body()))
                .containsExactlyElementsOf(expectedIds.subList(0, 2));
        Response beyondLastPage = get(path + "?page=3&size=2", owner.token());
        assertThat(beyondLastPage.status()).isEqualTo(200);
        assertPage(beyondLastPage.body(), 3, 2, 5, 3);
        assertThat(beyondLastPage.body().path("items").size()).isZero();
    }

    @Test
    void acceptsTheMaximumAddressPageSize() throws Exception {
        Account owner = createUser();
        Response response = get(addressPath(owner.id()) + "?size=100", owner.token());

        assertThat(response.status()).isEqualTo(200);
        assertPage(response.body(), 0, 100, 0, 0);
    }

    @ParameterizedTest
    @ValueSource(strings = { "page=-1", "size=0", "size=-1", "size=101", "page=abc", "size=abc",
            "page=2147483648", "size=2147483648", "page=2147483647&size=2" })
    void rejectsInvalidAddressPagination(String query) throws Exception {
        Account owner = createUser();

        assertProblem(get(addressPath(owner.id()) + "?" + query, owner.token()), 400);
    }

    @Test
    void rejectsMalformedUserAndAddressIdentifiers() throws Exception {
        Account known = createUser();

        assertProblem(get(USERS + "/not-a-uuid", known.token()), 400);
        assertProblem(put(USERS + "/not-a-uuid/profile", "{\"name\":\"Cliente\"}", known.token()), 400);
        assertProblem(post(addressPath("not-a-uuid"), ADDRESS_BODY, known.token()), 400);
        assertProblem(get(addressPath(known.id()) + "/not-a-uuid", known.token()), 400);
        assertProblem(put(addressPath(known.id()) + "/not-a-uuid", ADDRESS_BODY, known.token()), 400);
    }

    @Test
    void hidesInternalDetailsWhenAnUnexpectedApplicationErrorOccurs() throws Exception {
        UUID id = UUID.randomUUID();
        doThrow(new IllegalStateException("SQLException private-host internal-secret"))
                .when(users).findById(id);

        Response response = get(USERS + "/" + id, token(id.toString()));

        assertProblem(response, 500);
        assertThat(response.body().toString()).doesNotContain("private-host", "internal-secret", "IllegalStateException");
    }

    @Test
    void preservesPingAndActuatorAndDoesNotExposeAGlobalUserList() throws Exception {
        Response ping = get(USERS + "/ping");
        assertThat(ping.status()).isEqualTo(200);
        assertThat(ping.body().path("service").asString()).isEqualTo("user-service");
        assertThat(ping.body().path("status").asString()).isEqualTo("ok");

        Response health = get("/actuator/health");
        assertThat(health.status()).isEqualTo(200);
        assertThat(health.body().path("status").asString()).isEqualTo("UP");
        assertThat(get("/actuator/info").status()).isEqualTo(200);
        assertUnauthorized(get(USERS));
        Account account = createUser();
        assertThat(get(USERS, account.token()).status()).isIn(404, 405);
        assertThat(post(USERS, objectMapper.writeValueAsString(Map.of("name", "Cliente", "email", uniqueEmail())),
                account.token()).status()).isIn(404, 405);
    }

    private Account createUser() throws Exception {
        Response response = post(REGISTER, objectMapper.writeValueAsString(
                Map.of("name", "Cliente", "email", uniqueEmail(), "password", PASSWORD)));
        assertThat(response.status()).isEqualTo(201);
        return new Account(response.body(), token(response.body().path("id").asString()));
    }

    private JsonNode createAddress(Account owner, String label) throws Exception {
        Response response = post(addressPath(owner.id()), objectMapper.writeValueAsString(Map.of(
                "label", label, "address", "Rua A, 1", "latitude", 0, "longitude", 0)), owner.token());
        assertThat(response.status()).isEqualTo(201);
        return response.body();
    }

    private String token(String userId) {
        return tokens.issue(UUID.fromString(userId), Role.CUSTOMER).value();
    }

    private static String uniqueEmail() {
        return "customer-" + UUID.randomUUID() + "@example.com";
    }

    private static String addressPath(String userId) {
        return USERS + "/" + userId + "/addresses";
    }

    private static List<String> ids(JsonNode page) {
        return page.path("items").valueStream().map(address -> address.path("id").asString()).toList();
    }

    private static void assertPage(JsonNode body, int page, int size, long totalElements, long totalPages) {
        assertThat(body.size()).isEqualTo(5);
        assertThat(body.path("page").asInt()).isEqualTo(page);
        assertThat(body.path("size").asInt()).isEqualTo(size);
        assertThat(body.path("totalElements").asLong()).isEqualTo(totalElements);
        assertThat(body.path("totalPages").asLong()).isEqualTo(totalPages);
    }

    private static void assertUnauthorized(Response response) {
        assertProblem(response, 401);
        assertThat(response.headers().firstValue("WWW-Authenticate")).hasValueSatisfying(
                challenge -> assertThat(challenge).startsWith("Bearer"));
    }

    private static void assertProblem(Response response, int expectedStatus) {
        assertThat(response.status()).isEqualTo(expectedStatus);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                contentType -> assertThat(contentType).startsWith("application/problem+json"));
        assertThat(response.body().path("status").asInt()).isEqualTo(expectedStatus);
        assertThat(response.body().path("title").asString()).isNotBlank();
        assertThat(response.body().path("detail").asString()).isNotBlank();
        assertThat(response.body().has("exception")).isFalse();
        assertThat(response.body().has("trace")).isFalse();
        assertThat(response.body().toString()).doesNotContain("java.lang.", "org.springframework.",
                "SQLException", "stackTrace");
    }

    private Response get(String path) throws Exception {
        return get(path, null);
    }

    private Response get(String path, String token) throws Exception {
        return send(request(path, token).GET());
    }

    private Response post(String path, String body) throws Exception {
        return post(path, body, null);
    }

    private Response post(String path, String body, String token) throws Exception {
        return send(request(path, token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    private Response put(String path, String body) throws Exception {
        return put(path, body, null);
    }

    private Response put(String path, String body, String token) throws Exception {
        return send(request(path, token).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body)));
    }

    private HttpRequest.Builder request(String path, String token) {
        var request = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10));
        return token == null ? request : request.header("Authorization", "Bearer " + token);
    }

    private Response send(HttpRequest.Builder request) throws Exception {
        HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.headers(), objectMapper.readTree(response.body()));
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private record Response(int status, HttpHeaders headers, JsonNode body) {
    }

    private record Account(JsonNode profile, String token) {

        String id() {
            return profile.path("id").asString();
        }
    }
}
