package com.victhor.delivery.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.victhor.delivery.catalog.infrastructure.auth.TestAccessTokens;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "CATALOG_DB_PASSWORD=testcontainers-only")
@Testcontainers
@ActiveProfiles("test")
class MenuItemApiIntegrationTests {

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static final String VALID_ITEM = "{\"name\":\"Prato do dia\",\"price\":25.90}";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    private UUID restaurantId;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM menu_items");
        jdbc.update("DELETE FROM restaurants");
        restaurantId = insertRestaurant();
    }

    @Test
    void createsAnAvailableItemWithExactPriceAndServerOwnedIdentity() throws Exception {
        UUID suppliedId = UUID.randomUUID();
        Response created = request("POST", menuPath(restaurantId), objectMapper.writeValueAsString(Map.of(
                "name", "  Prato do dia  ", "description", "  Arroz e feijão  ", "price", new BigDecimal("25.900"),
                "id", suppliedId, "restaurantId", UUID.randomUUID(), "available", false, "currency", "USD")));

        assertThat(created.status()).isEqualTo(201);
        UUID id = UUID.fromString(created.body().path("id").asString());
        assertThat(id).isNotEqualTo(suppliedId);
        String path = menuPath(restaurantId) + "/" + id;
        assertThat(created.headers().firstValue("Location")).contains(path);
        assertThat(created.body().path("restaurantId").asString()).isEqualTo(restaurantId.toString());
        assertThat(created.body().path("name").asString()).isEqualTo("Prato do dia");
        assertThat(created.body().path("description").asString()).isEqualTo("Arroz e feijão");
        assertThat(created.body().path("price").isNumber()).isTrue();
        assertThat(created.body().path("price").asDouble()).isEqualTo(25.90);
        assertThat(created.body().path("currency").asString()).isEqualTo("BRL");
        assertThat(created.body().path("available").asBoolean()).isTrue();
        assertThat(created.body().size()).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT price FROM menu_items WHERE id = ?", BigDecimal.class, id))
                .isEqualTo(new BigDecimal("25.90"));
        assertThat(request("GET", path, null).body()).isEqualTo(created.body());
    }

    @Test
    void replacesItemDetailsAndAvailabilityWithoutChangingItsIdentity() throws Exception {
        Response created = request("POST", menuPath(restaurantId), VALID_ITEM);
        assertThat(created.status()).isEqualTo(201);
        String path = menuPath(restaurantId) + "/" + created.body().path("id").asString();
        String body = "{\"name\":\"Prato especial\",\"price\":30,\"available\":false}";

        Response updated = request("PUT", path, body);

        assertThat(updated.status()).isEqualTo(200);
        assertThat(updated.body().path("id")).isEqualTo(created.body().path("id"));
        assertThat(updated.body().path("restaurantId")).isEqualTo(created.body().path("restaurantId"));
        assertThat(updated.body().path("name").asString()).isEqualTo("Prato especial");
        assertThat(updated.body().path("description").isNull()).isTrue();
        assertThat(updated.body().path("price").asDouble()).isEqualTo(30);
        assertThat(updated.body().path("available").asBoolean()).isFalse();
        assertThat(request("GET", path, null).body()).isEqualTo(updated.body());
        assertThat(request("PUT", path, body).body()).isEqualTo(updated.body());
        assertThat(request("GET", menuPath(restaurantId), null).body().path("content").get(0))
                .isEqualTo(updated.body());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM menu_items", Integer.class)).isEqualTo(1);
    }

    @Test
    void doesNotExposeOrChangeAnItemThroughAnotherRestaurant() throws Exception {
        Response created = request("POST", menuPath(restaurantId), VALID_ITEM);
        assertThat(created.status()).isEqualTo(201);
        UUID otherRestaurant = insertRestaurant();
        String id = created.body().path("id").asString();

        assertProblem(request("GET", menuPath(otherRestaurant) + "/" + id, null), 404);
        assertProblem(request("PUT", menuPath(otherRestaurant) + "/" + id,
                "{\"name\":\"Alterado\",\"price\":1,\"available\":false}"), 404);
        assertThat(request("GET", menuPath(otherRestaurant), null).body().path("totalElements").asInt()).isZero();
        assertThat(request("GET", menuPath(restaurantId) + "/" + id, null).body()).isEqualTo(created.body());
    }

    @Test
    void distinguishesAnEmptyMenuFromAnUnknownRestaurantAndItem() throws Exception {
        Response empty = request("GET", menuPath(restaurantId), null);
        assertThat(empty.status()).isEqualTo(200);
        assertPage(empty.body(), 0, 20, 0, 0);
        assertThat(empty.body().path("content").size()).isZero();

        String missingMenu = menuPath(UUID.randomUUID());
        assertProblem(request("POST", missingMenu, VALID_ITEM), 404);
        assertProblem(request("GET", missingMenu, null), 404);
        assertProblem(request("GET", missingMenu + "/" + UUID.randomUUID(), null), 404);
        assertProblem(request("PUT", missingMenu + "/" + UUID.randomUUID(),
                "{\"name\":\"Prato\",\"price\":1,\"available\":true}"), 404);
        assertProblem(request("GET", menuPath(restaurantId) + "/" + UUID.randomUUID(), null), 404);
        assertProblem(request("PUT", menuPath(restaurantId) + "/" + UUID.randomUUID(),
                "{\"name\":\"Prato\",\"price\":1,\"available\":true}"), 404);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM menu_items", Integer.class)).isZero();
    }

    @Test
    void supportsMenuManagementForAnInactiveRestaurant() throws Exception {
        jdbc.update("UPDATE restaurants SET active = false WHERE id = ?", restaurantId);

        Response created = request("POST", menuPath(restaurantId), VALID_ITEM);

        assertThat(created.status()).isEqualTo(201);
        assertThat(request("GET", menuPath(restaurantId), null).body().path("totalElements").asInt()).isEqualTo(1);
    }

    @Test
    void paginatesOnlyThisRestaurantInStableNameAndUuidOrder() throws Exception {
        UUID otherRestaurant = insertRestaurant();
        for (String name : List.of("Zulu", "Alpha", "Delta", "Alpha", "Bravo")) {
            insertItem(restaurantId, name);
        }
        insertItem(otherRestaurant, "AAA");
        List<String> expected = jdbc.query("SELECT id FROM menu_items WHERE restaurant_id = ? ORDER BY name, id",
                (result, row) -> result.getObject("id", UUID.class).toString(), restaurantId);

        for (int page = 0; page < 4; page++) {
            Response response = request("GET", menuPath(restaurantId) + "?page=" + page + "&size=2", null);
            assertThat(response.status()).isEqualTo(200);
            assertPage(response.body(), page, 2, 5, 3);
            var ids = response.body().path("content").valueStream().map(item -> item.path("id").asString()).toList();
            assertThat(ids).containsExactlyElementsOf(expected.subList(Math.min(page * 2, 5), Math.min(page * 2 + 2, 5)));
        }
        assertPage(request("GET", menuPath(restaurantId) + "?size=100", null).body(), 0, 100, 5, 1);
    }

    @ParameterizedTest
    @MethodSource("invalidCreateBodies")
    void rejectsInvalidInputWithoutCreatingAnItem(String body) throws Exception {
        assertProblem(request("POST", menuPath(restaurantId), body), 400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM menu_items", Integer.class)).isZero();
    }

    static Stream<String> invalidCreateBodies() {
        return Stream.of("{}", "null", "[]", "{", "", "{\"name\":\"Prato\"}",
                "{\"name\":123,\"price\":1}", "{\"name\":\" \",\"price\":1}",
                "{\"name\":\"" + "a".repeat(121) + "\",\"price\":1}",
                "{\"name\":\"Prato\",\"description\":true,\"price\":1}",
                "{\"name\":\"Prato\",\"description\":\"" + "a".repeat(1001) + "\",\"price\":1}",
                "{\"name\":\"Prato\",\"price\":null}", "{\"name\":\"Prato\",\"price\":\"25.90\"}",
                "{\"name\":\"Prato\",\"price\":true}", "{\"name\":\"Prato\",\"price\":0}",
                "{\"name\":\"Prato\",\"price\":-1}", "{\"name\":\"Prato\",\"price\":0.001}",
                "{\"name\":\"Prato\",\"price\":25.901}", "{\"name\":\"Prato\",\"price\":100000000}",
                "{\"name\":\"Prato\",\"price\":1e309}");
    }

    @ParameterizedTest
    @MethodSource("invalidUpdateBodies")
    void rejectsInvalidUpdatesWithoutChangingTheStoredItem(String body) throws Exception {
        Response created = request("POST", menuPath(restaurantId), VALID_ITEM);
        assertThat(created.status()).isEqualTo(201);
        String path = menuPath(restaurantId) + "/" + created.body().path("id").asString();

        assertProblem(request("PUT", path, body), 400);
        assertThat(request("GET", path, null).body()).isEqualTo(created.body());
    }

    static Stream<String> invalidUpdateBodies() {
        return Stream.of("null", "{}", "{\"name\":\"Prato\",\"price\":1}",
                "{\"name\":\"Prato\",\"price\":1,\"available\":null}",
                "{\"name\":\"Prato\",\"price\":1,\"available\":\"false\"}",
                "{\"name\":\"Prato\",\"price\":1,\"available\":0}",
                "{\"name\":\"Prato\",\"price\":1,\"available\":1.0}",
                "{\"name\":\"Prato\",\"price\":1.001,\"available\":false}");
    }

    @ParameterizedTest
    @ValueSource(strings = { "page=-1", "size=0", "size=101", "page=abc", "page=2147483648",
            "page=2147483647&size=2" })
    void rejectsInvalidPagination(String query) throws Exception {
        assertProblem(request("GET", menuPath(restaurantId) + "?" + query, null), 400);
    }

    @Test
    void rejectsMalformedIds() throws Exception {
        assertProblem(request("GET", "/api/catalog/restaurants/invalid/menu-items", null), 400);
        assertProblem(request("GET", menuPath(restaurantId) + "/invalid", null), 400);
    }

    private UUID insertRestaurant() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO restaurants (id, name) VALUES (?, ?)", id, "Cantina");
        return id;
    }

    private void insertItem(UUID owner, String name) {
        jdbc.update("INSERT INTO menu_items (id, restaurant_id, name, price) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), owner, name, new BigDecimal("1.00"));
    }

    private String menuPath(UUID owner) {
        return "/api/catalog/restaurants/" + owner + "/menu-items";
    }

    private Response request(String method, String path, String body) throws Exception {
        var publisher = body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body);
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + TestAccessTokens.issueOperator(UUID.randomUUID()))
                .method(method, publisher).build();
        var response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), objectMapper.readTree(response.body()), response.headers());
    }

    private void assertProblem(Response response, int status) {
        assertThat(response.status()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/problem+json"));
        assertThat(response.body().path("status").asInt()).isEqualTo(status);
        assertThat(response.body().path("detail").asString()).isNotBlank();
        assertThat(response.body().has("trace")).isFalse();
        assertThat(response.body().has("exception")).isFalse();
    }

    private void assertPage(JsonNode body, int page, int size, int totalElements, int totalPages) {
        assertThat(body.path("page").asInt()).isEqualTo(page);
        assertThat(body.path("size").asInt()).isEqualTo(size);
        assertThat(body.path("totalElements").asInt()).isEqualTo(totalElements);
        assertThat(body.path("totalPages").asInt()).isEqualTo(totalPages);
    }

    private record Response(int status, JsonNode body, HttpHeaders headers) {
    }
}
