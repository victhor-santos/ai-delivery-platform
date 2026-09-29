package com.victhor.delivery.catalog;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = "CATALOG_DB_PASSWORD=testcontainers-only")
@Testcontainers
class RestaurantApiIntegrationTests {

	private static final String RESTAURANTS = "/api/catalog/restaurants";
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

	@LocalServerPort
	private int port;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private ObjectMapper objectMapper;

	@BeforeEach
	void clearRestaurantsInTestContainer() {
		jdbc.update("DELETE FROM restaurants");
	}

	@Test
	void createsAnActiveRestaurantWithGeneratedIdAndPersistsIt() throws Exception {
		Response response = post("{\"name\":\"  Cantina Vitória  \"}");

		assertThat(response.status()).isEqualTo(201);
		UUID id = UUID.fromString(response.body().path("id").asText());
		assertThat(response.headers().firstValue("Location")).contains(RESTAURANTS + "/" + id);
		assertThat(response.body().path("name").asText()).isEqualTo("Cantina Vitória");
		assertThat(response.body().path("active").asBoolean()).isTrue();
		assertThat(response.body().size()).isEqualTo(3);

		Map<String, Object> stored = jdbc.queryForMap("SELECT id, name, active FROM restaurants WHERE id = ?", id);
		assertThat(stored).containsEntry("id", id)
				.containsEntry("name", "Cantina Vitória")
				.containsEntry("active", true);
	}

	@Test
	void ignoresClientSuppliedIdAndActiveFlag() throws Exception {
		UUID suppliedId = UUID.randomUUID();
		Response response = post("{\"name\":\"Cantina\",\"id\":\"" + suppliedId + "\",\"active\":false}");

		assertThat(response.status()).isEqualTo(201);
		assertThat(UUID.fromString(response.body().path("id").asText())).isNotEqualTo(suppliedId);
		assertThat(response.body().path("active").asBoolean()).isTrue();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM restaurants WHERE id = ?", Integer.class, suppliedId))
				.isZero();
	}

	@Test
	void acceptsANameAtTheMaximumLengthAfterRemovingSurroundingWhitespace() throws Exception {
		Response response = post(objectMapper.writeValueAsString(Map.of("name", "  " + "a".repeat(120) + "  ")));

		assertThat(response.status()).isEqualTo(201);
		assertThat(response.body().path("name").asText()).hasSize(120);
	}

	@Test
	void findsAPersistedRestaurantById() throws Exception {
		Response created = post("{\"name\":\"Pizzaria Central\"}");
		assertThat(created.status()).isEqualTo(201);

		Response response = get(RESTAURANTS + "/" + created.body().path("id").asText());

		assertThat(response.status()).isEqualTo(200);
		assertThat(response.body()).isEqualTo(created.body());
	}

	@Test
	void returnsAnEmptyPageWithDefaultPagination() throws Exception {
		Response response = get(RESTAURANTS);

		assertThat(response.status()).isEqualTo(200);
		assertPage(response.body(), 0, 20, 0, 0);
		assertThat(response.body().path("content").isArray()).isTrue();
		assertThat(response.body().path("content").size()).isZero();
	}

	@Test
	void paginatesWithStableNameAndUuidOrdering() throws Exception {
		insertRestaurant("ffffffff-ffff-ffff-ffff-ffffffffffff", "Alpha");
		insertRestaurant("00000000-0000-0000-0000-000000000004", "Zulu");
		insertRestaurant("00000000-0000-0000-0000-000000000003", "Bravo");
		insertRestaurant("00000000-0000-0000-0000-000000000001", "Alpha");
		insertRestaurant("00000000-0000-0000-0000-000000000005", "Delta");
		List<String> expectedIds = jdbc.query("SELECT id FROM restaurants ORDER BY name ASC, id ASC",
				(result, row) -> result.getObject("id", UUID.class).toString());

		for (int page = 0; page < 3; page++) {
			Response response = get(RESTAURANTS + "?page=" + page + "&size=2");
			assertThat(response.status()).isEqualTo(200);
			assertPage(response.body(), page, 2, 5, 3);
			assertThat(ids(response.body())).containsExactlyElementsOf(
					expectedIds.subList(page * 2, Math.min(page * 2 + 2, expectedIds.size())));
		}

		assertThat(ids(get(RESTAURANTS + "?page=0&size=2").body()))
				.containsExactlyElementsOf(expectedIds.subList(0, 2));
		Response beyondLastPage = get(RESTAURANTS + "?page=3&size=2");
		assertThat(beyondLastPage.status()).isEqualTo(200);
		assertPage(beyondLastPage.body(), 3, 2, 5, 3);
		assertThat(beyondLastPage.body().path("content").size()).isZero();
	}

	@Test
	void acceptsTheMaximumPageSize() throws Exception {
		Response response = get(RESTAURANTS + "?size=100");

		assertThat(response.status()).isEqualTo(200);
		assertPage(response.body(), 0, 100, 0, 0);
	}

	@ParameterizedTest
	@MethodSource("invalidBodies")
	void rejectsInvalidInputWithoutPersistingIt(String requestBody) throws Exception {
		assertProblem(post(requestBody), 400);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM restaurants", Integer.class)).isZero();
	}

	static Stream<String> invalidBodies() {
		return Stream.of("{}", "{\"name\":null}", "{\"name\":\"\"}", "{\"name\":\"   \\t\\n\"}",
				"{\"name\":\"" + "a".repeat(121) + "\"}", "{", "", "null", "[]", "{\"name\":{}}",
				"{\"name\":123}", "{\"name\":1.5}", "{\"name\":true}", "{\"name\":\"\u2003\"}");
	}

	@ParameterizedTest
	@ValueSource(strings = { "page=-1", "size=0", "size=-1", "size=101", "page=abc", "size=abc",
			"page=2147483648", "size=2147483648", "page=2147483647&size=2" })
	void rejectsInvalidPagination(String query) throws Exception {
		assertProblem(get(RESTAURANTS + "?" + query), 400);
	}

	@Test
	void rejectsAnInvalidUuid() throws Exception {
		assertProblem(get(RESTAURANTS + "/not-a-uuid"), 400);
	}

	@Test
	void returnsNotFoundForAnUnknownRestaurant() throws Exception {
		assertProblem(get(RESTAURANTS + "/" + UUID.randomUUID()), 404);
	}

	@Test
	void preservesPingAndActuatorEndpoints() throws Exception {
		Response ping = get("/api/catalog/ping");
		assertThat(ping.status()).isEqualTo(200);
		assertThat(ping.body().path("service").asText()).isEqualTo("catalog-service");
		assertThat(ping.body().path("status").asText()).isEqualTo("ok");

		Response health = get("/actuator/health");
		assertThat(health.status()).isEqualTo(200);
		assertThat(health.body().path("status").asText()).isEqualTo("UP");
		assertThat(get("/actuator/info").status()).isEqualTo(200);
	}

	private void insertRestaurant(String id, String name) {
		jdbc.update("INSERT INTO restaurants (id, name, active) VALUES (?, ?, ?)", UUID.fromString(id), name, true);
	}

	private void assertPage(JsonNode body, int page, int size, long totalElements, int totalPages) {
		for (String field : List.of("content", "page", "size", "totalElements", "totalPages")) {
			assertThat(body.has(field)).as("page response field %s", field).isTrue();
		}
		assertThat(body.path("page").asInt()).isEqualTo(page);
		assertThat(body.path("size").asInt()).isEqualTo(size);
		assertThat(body.path("totalElements").asLong()).isEqualTo(totalElements);
		assertThat(body.path("totalPages").asInt()).isEqualTo(totalPages);
	}

	private List<String> ids(JsonNode page) {
		return page.path("content").valueStream().map(restaurant -> restaurant.path("id").asText()).toList();
	}

	private void assertProblem(Response response, int expectedStatus) {
		assertThat(response.status()).isEqualTo(expectedStatus);
		assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
				contentType -> assertThat(contentType).startsWith("application/problem+json"));
		assertThat(response.body().path("status").asInt()).isEqualTo(expectedStatus);
		assertThat(response.body().path("title").asText()).isNotBlank();
		assertThat(response.body().path("detail").asText()).isNotBlank();
		assertThat(response.body().has("exception")).isFalse();
		assertThat(response.body().has("trace")).isFalse();
		assertThat(response.body().toString()).doesNotContain("java.lang.", "org.springframework.",
				"SQLException", "stackTrace");
	}

	private Response get(String path) throws Exception {
		return send(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10)).GET().build());
	}

	private Response post(String body) throws Exception {
		return send(HttpRequest.newBuilder(uri(RESTAURANTS)).timeout(Duration.ofSeconds(10))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body)).build());
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
