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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.victhor.delivery.user.application.UserProfileService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "USER_DB_PASSWORD=testcontainers-only")
@Testcontainers
class UserProfileApiTests {

    private static final String USERS = "/api/users";
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

    @MockitoSpyBean
    private UserProfileService users;

    @Test
    void createsAndQueriesAUserWithGeneratedIdAndCanonicalEmail() throws Exception {
        String email = uniqueEmail();
        Response created = post(USERS, objectMapper.writeValueAsString(Map.of(
                "name", "  Maria Vitória  ", "email", "  " + email.toUpperCase(Locale.ROOT) + "  ")));

        assertThat(created.status()).isEqualTo(201);
        String id = created.body().path("id").asString();
        assertThat(UUID.fromString(id)).isNotNull();
        assertThat(created.headers().firstValue("Location")).contains(USERS + "/" + id);
        assertThat(created.body().path("name").asString()).isEqualTo("Maria Vitória");
        assertThat(created.body().path("email").asString()).isEqualTo(email);
        assertThat(created.body().size()).isEqualTo(3);

        Response found = get(USERS + "/" + id);
        assertThat(found.status()).isEqualTo(200);
        assertThat(found.body()).isEqualTo(created.body());
    }

    @Test
    void updatesOnlyTheNameAndKeepsTheRegisteredEmailAndIdentity() throws Exception {
        Response created = createUser();
        String path = USERS + "/" + created.body().path("id").asString();

        Response updated = put(path + "/profile", """
                {"name":"  Nome atualizado  ","email":"ignored@example.com","id":"ignored","role":"ADMIN"}
                """);

        assertThat(updated.status()).isEqualTo(200);
        assertThat(updated.body().path("name").asString()).isEqualTo("Nome atualizado");
        assertThat(updated.body().path("email")).isEqualTo(created.body().path("email"));
        assertThat(updated.body().path("id")).isEqualTo(created.body().path("id"));
        assertThat(updated.body().size()).isEqualTo(3);
        assertThat(get(path).body()).isEqualTo(updated.body());
        assertThat(put(path + "/profile", "{\"name\":\"Nome atualizado\"}").body())
                .isEqualTo(updated.body());
    }

    @Test
    void ignoresClientSuppliedIdentityAndOtherUnrecognizedCreationFields() throws Exception {
        UUID suppliedId = UUID.randomUUID();
        Response created = post(USERS, objectMapper.writeValueAsString(Map.of(
                "name", "Cliente", "email", uniqueEmail(), "id", suppliedId,
                "password", "unused", "role", "ADMIN")));

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body().path("id").asString()).isNotEqualTo(suppliedId.toString());
        assertThat(created.body().size()).isEqualTo(3);
        assertProblem(get(USERS + "/" + suppliedId), 404);
    }

    @Test
    void rejectsDuplicateEmailsAfterWhitespaceAndCaseNormalization() throws Exception {
        Response created = createUser();
        String email = created.body().path("email").asString();
        Response duplicate = post(USERS, objectMapper.writeValueAsString(Map.of(
                "name", "Outro cliente", "email", " " + email.toUpperCase(Locale.ROOT) + " ")));

        assertProblem(duplicate, 409);
        assertThat(duplicate.body().toString()).doesNotContain(email, "Outro cliente");
        assertThat(get(USERS + "/" + created.body().path("id").asString()).body())
                .isEqualTo(created.body());
    }

    @Test
    void acceptsTheMaximumNameAndEmailLengthsAfterTrimming() throws Exception {
        String email = "a".repeat(64) + "@" + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(61);
        assertThat(email).hasSize(254);

        Response response = post(USERS, objectMapper.writeValueAsString(Map.of(
                "name", "  " + "N".repeat(120) + "  ", "email", "  " + email + "  ")));

        assertThat(response.status()).isEqualTo(201);
        assertThat(response.body().path("name").asString()).hasSize(120);
        assertThat(response.body().path("email").asString()).isEqualTo(email);
    }

    @ParameterizedTest
    @MethodSource("invalidUserBodies")
    void rejectsMalformedOrInvalidUserCreation(String body) throws Exception {
        assertProblem(post(USERS, body), 400);
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
        Response created = createUser();
        String path = USERS + "/" + created.body().path("id").asString();

        assertProblem(put(path + "/profile", body), 400);

        assertThat(get(path).body()).isEqualTo(created.body());
    }

    static Stream<String> invalidProfileBodies() {
        return Stream.of("", "{", "null", "[]", "{}", "{\"name\":null}", "{\"name\":\"\"}",
                "{\"name\":\"  \"}", "{\"name\":\"\u2003\"}", "{\"name\":123}",
                "{\"name\":1.5}", "{\"name\":true}", "{\"name\":{}}",
                "{\"name\":\"" + "n".repeat(121) + "\"}");
    }

    @Test
    void createsQueriesAndReplacesAnAddressWithoutChangingOwnershipOrIdentity() throws Exception {
        String userId = createUser().body().path("id").asString();
        String path = addressPath(userId);
        Response created = post(path, """
                {"label":"  Casa  ","address":"  Rua das Flores, 10  ","latitude":-23.5505,"longitude":-46.6333}
                """);

        assertThat(created.status()).isEqualTo(201);
        String id = created.body().path("id").asString();
        assertThat(UUID.fromString(id)).isNotNull();
        assertThat(created.headers().firstValue("Location")).contains(path + "/" + id);
        assertThat(created.body().path("userId").asString()).isEqualTo(userId);
        assertThat(created.body().path("label").asString()).isEqualTo("Casa");
        assertThat(created.body().path("address").asString()).isEqualTo("Rua das Flores, 10");
        assertThat(created.body().path("latitude").asDouble()).isEqualTo(-23.5505);
        assertThat(created.body().path("longitude").asDouble()).isEqualTo(-46.6333);
        assertThat(created.body().size()).isEqualTo(6);
        assertThat(get(path + "/" + id).body()).isEqualTo(created.body());

        Response updated = put(path + "/" + id, objectMapper.writeValueAsString(Map.of(
                "label", "Trabalho", "address", "Avenida Central, 20", "latitude", 0,
                "longitude", 180, "id", UUID.randomUUID(), "userId", UUID.randomUUID())));

        assertThat(updated.status()).isEqualTo(200);
        assertThat(updated.body().path("id")).isEqualTo(created.body().path("id"));
        assertThat(updated.body().path("userId")).isEqualTo(created.body().path("userId"));
        assertThat(updated.body().path("label").asString()).isEqualTo("Trabalho");
        assertThat(updated.body().path("address").asString()).isEqualTo("Avenida Central, 20");
        assertThat(updated.body().path("latitude").asDouble()).isZero();
        assertThat(updated.body().path("longitude").asDouble()).isEqualTo(180);
        assertThat(get(path + "/" + id).body()).isEqualTo(updated.body());
        assertThat(get(path).body().path("totalElements").asLong()).isEqualTo(1);
    }

    @Test
    void generatesAddressIdentityAndKeepsTheOwnerFromThePath() throws Exception {
        String userId = createUser().body().path("id").asString();
        String otherId = createUser().body().path("id").asString();
        UUID suppliedId = UUID.randomUUID();
        Response created = post(addressPath(userId), objectMapper.writeValueAsString(Map.of(
                "label", "Casa", "address", "Rua A, 1", "latitude", 0, "longitude", 0,
                "id", suppliedId, "userId", otherId)));

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body().path("id").asString()).isNotEqualTo(suppliedId.toString());
        assertThat(created.body().path("userId").asString()).isEqualTo(userId);
        assertThat(get(addressPath(otherId)).body().path("totalElements").asLong()).isZero();
    }

    @Test
    void acceptsTheMaximumAddressLengthsAfterTrimming() throws Exception {
        String path = addressPath(createUser().body().path("id").asString());
        Response created = post(path, objectMapper.writeValueAsString(Map.of(
                "label", "  " + "L".repeat(80) + "  ", "address", "  " + "A".repeat(255) + "  ",
                "latitude", 0, "longitude", 0)));

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body().path("label").asString()).hasSize(80);
        assertThat(created.body().path("address").asString()).hasSize(255);
    }

    @ParameterizedTest
    @MethodSource("boundaryCoordinates")
    void acceptsFiniteCoordinateBoundaries(double latitude, double longitude) throws Exception {
        String path = addressPath(createUser().body().path("id").asString());
        Response created = post(path, objectMapper.writeValueAsString(Map.of(
                "label", "Local", "address", "Rua A", "latitude", latitude, "longitude", longitude)));

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body().path("latitude").asDouble()).isEqualTo(latitude);
        assertThat(created.body().path("longitude").asDouble()).isEqualTo(longitude);
    }

    static Stream<Arguments> boundaryCoordinates() {
        return Stream.of(Arguments.of(-90, -180), Arguments.of(90, 180));
    }

    @Test
    void returnsNotFoundForAddressesOwnedByAnotherUserWithoutChangingThem() throws Exception {
        String ownerId = createUser().body().path("id").asString();
        String otherId = createUser().body().path("id").asString();
        Response created = post(addressPath(ownerId), ADDRESS_BODY);
        assertThat(created.status()).isEqualTo(201);
        String id = created.body().path("id").asString();

        assertProblem(get(addressPath(otherId) + "/" + id), 404);
        assertProblem(put(addressPath(otherId) + "/" + id, ADDRESS_BODY), 404);

        assertThat(get(addressPath(ownerId) + "/" + id).body()).isEqualTo(created.body());
        assertThat(get(addressPath(otherId)).body().path("items").size()).isZero();
    }

    @Test
    void returnsNotFoundForUnknownUsersAndAddresses() throws Exception {
        String missingUser = UUID.randomUUID().toString();
        String missingAddress = UUID.randomUUID().toString();
        String knownUser = createUser().body().path("id").asString();

        assertProblem(get(USERS + "/" + missingUser), 404);
        assertProblem(put(USERS + "/" + missingUser + "/profile", "{\"name\":\"Novo nome\"}"), 404);
        assertProblem(get(addressPath(missingUser)), 404);
        assertProblem(post(addressPath(missingUser), ADDRESS_BODY), 404);
        assertProblem(get(addressPath(missingUser) + "/" + missingAddress), 404);
        assertProblem(put(addressPath(missingUser) + "/" + missingAddress, ADDRESS_BODY), 404);
        assertProblem(get(addressPath(knownUser) + "/" + missingAddress), 404);
        assertProblem(put(addressPath(knownUser) + "/" + missingAddress, ADDRESS_BODY), 404);
    }

    @ParameterizedTest
    @MethodSource("invalidAddressBodies")
    void rejectsInvalidAddressCreationAndReplacementWithoutChangingStoredData(String body) throws Exception {
        String path = addressPath(createUser().body().path("id").asString());
        Response created = post(path, ADDRESS_BODY);
        assertThat(created.status()).isEqualTo(201);
        String address = path + "/" + created.body().path("id").asString();

        assertProblem(post(path, body), 400);
        assertProblem(put(address, body), 400);

        assertThat(get(address).body()).isEqualTo(created.body());
        assertThat(get(path).body().path("totalElements").asLong()).isEqualTo(1);
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
                "{\"label\":\"Casa\",\"address\":\"\u2003\",\"latitude\":0,\"longitude\":0}",
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
        String path = addressPath(createUser().body().path("id").asString());
        Response response = get(path);

        assertThat(response.status()).isEqualTo(200);
        assertPage(response.body(), 0, 20, 0, 0);
        assertThat(response.body().path("items").isArray()).isTrue();
        assertThat(response.body().path("items").size()).isZero();
    }

    @Test
    void paginatesOnlyTheUsersAddressesWithStableLabelAndUuidOrdering() throws Exception {
        String path = addressPath(createUser().body().path("id").asString());
        String otherPath = addressPath(createUser().body().path("id").asString());
        assertThat(post(otherPath, ADDRESS_BODY).status()).isEqualTo(201);
        List<JsonNode> addresses = List.of(createAddress(path, "Zulu"), createAddress(path, "Alpha"),
                createAddress(path, "Bravo"), createAddress(path, "Alpha"), createAddress(path, "Delta"));
        List<String> expectedIds = addresses.stream()
                .sorted(Comparator.comparing((JsonNode address) -> address.path("label").asString())
                        .thenComparing(address -> address.path("id").asString()))
                .map(address -> address.path("id").asString()).toList();

        for (int page = 0; page < 3; page++) {
            Response response = get(path + "?page=" + page + "&size=2");
            assertThat(response.status()).isEqualTo(200);
            assertPage(response.body(), page, 2, 5, 3);
            assertThat(ids(response.body())).containsExactlyElementsOf(
                    expectedIds.subList(page * 2, Math.min(page * 2 + 2, expectedIds.size())));
        }

        assertThat(ids(get(path + "?page=0&size=2").body()))
                .containsExactlyElementsOf(expectedIds.subList(0, 2));
        Response beyondLastPage = get(path + "?page=3&size=2");
        assertThat(beyondLastPage.status()).isEqualTo(200);
        assertPage(beyondLastPage.body(), 3, 2, 5, 3);
        assertThat(beyondLastPage.body().path("items").size()).isZero();
    }

    @Test
    void acceptsTheMaximumAddressPageSize() throws Exception {
        String path = addressPath(createUser().body().path("id").asString());
        Response response = get(path + "?size=100");

        assertThat(response.status()).isEqualTo(200);
        assertPage(response.body(), 0, 100, 0, 0);
    }

    @ParameterizedTest
    @ValueSource(strings = { "page=-1", "size=0", "size=-1", "size=101", "page=abc", "size=abc",
            "page=2147483648", "size=2147483648", "page=2147483647&size=2" })
    void rejectsInvalidAddressPagination(String query) throws Exception {
        String path = addressPath(createUser().body().path("id").asString());

        assertProblem(get(path + "?" + query), 400);
    }

    @Test
    void rejectsMalformedUserAndAddressIdentifiers() throws Exception {
        String knownUser = createUser().body().path("id").asString();

        assertProblem(get(USERS + "/not-a-uuid"), 400);
        assertProblem(put(USERS + "/not-a-uuid/profile", "{\"name\":\"Cliente\"}"), 400);
        assertProblem(post(addressPath("not-a-uuid"), ADDRESS_BODY), 400);
        assertProblem(get(addressPath(knownUser) + "/not-a-uuid"), 400);
        assertProblem(put(addressPath(knownUser) + "/not-a-uuid", ADDRESS_BODY), 400);
    }

    @Test
    void hidesInternalDetailsWhenAnUnexpectedApplicationErrorOccurs() throws Exception {
        UUID id = UUID.randomUUID();
        doThrow(new IllegalStateException("SQLException private-host internal-secret"))
                .when(users).findById(id);

        Response response = get(USERS + "/" + id);

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
        assertProblem(get(USERS), 405);
    }

    private Response createUser() throws Exception {
        Response response = post(USERS,
                objectMapper.writeValueAsString(Map.of("name", "Cliente", "email", uniqueEmail())));
        assertThat(response.status()).isEqualTo(201);
        return response;
    }

    private JsonNode createAddress(String path, String label) throws Exception {
        Response response = post(path, objectMapper.writeValueAsString(Map.of(
                "label", label, "address", "Rua A, 1", "latitude", 0, "longitude", 0)));
        assertThat(response.status()).isEqualTo(201);
        return response.body();
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
        return send(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10)).GET().build());
    }

    private Response post(String path, String body) throws Exception {
        return send(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    private Response put(String path, String body) throws Exception {
        return send(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    private Response send(HttpRequest request) throws Exception {
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.headers(), objectMapper.readTree(response.body()));
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private record Response(int status, HttpHeaders headers, JsonNode body) {
    }
}
