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
class RestaurantApiIntegrationTests {

	private static final String RESTAURANTS = "/api/catalog/restaurants";
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	private static final String OPERATOR = "Bearer " + TestAccessTokens.issueOperator(UUID.randomUUID());

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
	void letsAnyoneBrowseButOnlyOperatorsChangeTheCatalog() throws Exception {
		String id = post("{\"name\":\"Cantina\"}").body().path("id").asString();
		String location = "{\"latitude\":-23.5,\"longitude\":-46.6}";
		String customer = "Bearer " + TestAccessTokens.issue(UUID.randomUUID());
		String foreign = "Bearer " + TestAccessTokens.issue(TestAccessTokens.FOREIGN_PRIVATE_KEY, java.time.Instant.now(),
				claims -> claims.claim("roles", List.of("OPERATOR")));

		assertThat(get(RESTAURANTS).status()).isEqualTo(200);
		assertThat(get(RESTAURANTS + "/" + id).status()).isEqualTo(200);
		for (String authorization : new String[] { null, "Bearer not-a-token", foreign }) {
			assertProblem(send(write("POST", RESTAURANTS, "{\"name\":\"Outro\"}", authorization)), 401);
			assertProblem(send(write("PUT", RESTAURANTS + "/" + id + "/pickup-location", location, authorization)), 401);
		}
		var denied = send(write("POST", RESTAURANTS, "{\"name\":\"Outro\"}", customer));
		assertProblem(denied, 403);
		assertThat(denied.body().path("detail").asString()).isEqualTo("Operação permitida apenas a operadores.");
		assertProblem(send(write("PUT", RESTAURANTS + "/" + id + "/pickup-location", location, customer)), 403);
		assertProblem(send(write("DELETE", RESTAURANTS + "/" + id, null, "Bearer " + TestAccessTokens.issueOperator(
				UUID.randomUUID()))), 403);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM restaurants", Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT pickup_latitude FROM restaurants", Double.class)).isNull();
	}

	@Test
	void createsAnActiveRestaurantWithGeneratedIdAndPersistsIt() throws Exception {
		Response response = post("{\"name\":\"  Cantina Vitória  \"}");

		assertThat(response.status()).isEqualTo(201);
		UUID id = UUID.fromString(response.body().path("id").asString());
		assertThat(response.headers().firstValue("Location")).contains(RESTAURANTS + "/" + id);
		assertThat(response.body().path("name").asString()).isEqualTo("Cantina Vitória");
		assertThat(response.body().path("active").asBoolean()).isTrue();
		assertThat(response.body().size()).isEqualTo(4);
		assertThat(response.body().path("pickupLocation").isNull()).isTrue();

		Map<String, Object> stored = jdbc.queryForMap("SELECT id, name, active FROM restaurants WHERE id = ?", id);
		assertThat(stored).containsEntry("id", id)
				.containsEntry("name", "Cantina Vitória")
				.containsEntry("active", true);
	}

	@Test
	void createsAndQueriesARestaurantWithPickupLocation() throws Exception {
		Response created = post("""
				{"name":"Cantina","pickupLocation":{"latitude":-23.5505,"longitude":-46.6333}}
				""");

		assertThat(created.status()).isEqualTo(201);
		String id = created.body().path("id").asString();
		assertThat(created.headers().firstValue("Location")).contains(RESTAURANTS + "/" + id);
		assertThat(created.body().path("pickupLocation").path("latitude").asDouble()).isEqualTo(-23.5505);
		assertThat(created.body().path("pickupLocation").path("longitude").asDouble()).isEqualTo(-46.6333);
		assertThat(get(RESTAURANTS + "/" + id).body()).isEqualTo(created.body());
		assertThat(get(RESTAURANTS).body().path("content").get(0)).isEqualTo(created.body());
		assertThat(jdbc.queryForMap("SELECT pickup_latitude, pickup_longitude FROM restaurants WHERE id = ?",
				UUID.fromString(id))).containsEntry("pickup_latitude", -23.5505)
				.containsEntry("pickup_longitude", -46.6333);
	}

	@Test
	void acceptsAnExplicitlyNullLocationOnCreation() throws Exception {
		Response response = post("{\"name\":\"Cantina\",\"pickupLocation\":null}");

		assertThat(response.status()).isEqualTo(201);
		assertThat(response.body().path("pickupLocation").isNull()).isTrue();
	}

	@Test
	void assignsAndReplacesPickupLocationWithoutChangingRestaurantIdentity() throws Exception {
		Response created = post("{\"name\":\"Cantina\"}");
		assertThat(created.status()).isEqualTo(201);
		String id = created.body().path("id").asString();
		jdbc.update("UPDATE restaurants SET active = false WHERE id = ?", UUID.fromString(id));
		String path = RESTAURANTS + "/" + id + "/pickup-location";
		assertThat(put(path, "{\"latitude\":-23.5505,\"longitude\":-46.6333}").status()).isEqualTo(200);

		Response updated = put(path, "{\"latitude\":0,\"longitude\":180}");

		assertThat(updated.status()).isEqualTo(200);
		assertThat(updated.body().path("id").asString()).isEqualTo(id);
		assertThat(updated.body().path("name").asString()).isEqualTo("Cantina");
		assertThat(updated.body().path("active").asBoolean()).isFalse();
		assertThat(updated.body().path("pickupLocation").path("latitude").asDouble()).isZero();
		assertThat(updated.body().path("pickupLocation").path("longitude").asDouble()).isEqualTo(180);
		assertThat(get(RESTAURANTS + "/" + id).body()).isEqualTo(updated.body());
		Response repeated = put(path, "{\"latitude\":0,\"longitude\":180}");
		assertThat(repeated.status()).isEqualTo(200);
		assertThat(repeated.body()).isEqualTo(updated.body());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM restaurants", Integer.class)).isEqualTo(1);
	}

	@ParameterizedTest
	@MethodSource("invalidLocations")
	void rejectsInvalidLocationsWithoutCreatingOrChangingRestaurants(String location) throws Exception {
		assertProblem(post("{\"name\":\"Cantina\",\"pickupLocation\":" + location + "}"), 400);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM restaurants", Integer.class)).isZero();
		Response created = post("""
				{"name":"Cantina","pickupLocation":{"latitude":-23.5505,"longitude":-46.6333}}
				""");
		assertThat(created.status()).isEqualTo(201);
		String path = RESTAURANTS + "/" + created.body().path("id").asString();

		assertProblem(put(path + "/pickup-location", location), 400);

		assertThat(get(path).body()).isEqualTo(created.body());
	}

	static Stream<String> invalidLocations() {
		return Stream.of("{}", "[]", "true",
				"{\"latitude\":0}", "{\"longitude\":0}",
				"{\"latitude\":null,\"longitude\":0}", "{\"latitude\":0,\"longitude\":null}",
				"{\"latitude\":90.000001,\"longitude\":0}", "{\"latitude\":-90.000001,\"longitude\":0}",
				"{\"latitude\":0,\"longitude\":180.000001}", "{\"latitude\":0,\"longitude\":-180.000001}",
				"{\"latitude\":\"-23.5505\",\"longitude\":0}", "{\"latitude\":0,\"longitude\":\"\"}",
				"{\"latitude\":true,\"longitude\":0}", "{\"latitude\":0,\"longitude\":\"NaN\"}",
				"{\"latitude\":1e309,\"longitude\":0}");
	}

	@ParameterizedTest
	@ValueSource(strings = { "null", "", "{" })
	void rejectsAnEmptyOrMalformedLocationUpdate(String body) throws Exception {
		Response created = post("{\"name\":\"Cantina\"}");
		assertThat(created.status()).isEqualTo(201);
		String path = RESTAURANTS + "/" + created.body().path("id").asString();

		assertProblem(put(path + "/pickup-location", body), 400);
		assertThat(get(path).body()).isEqualTo(created.body());
	}

	@Test
	void rejectsAnUnknownRestaurantOrMalformedIdWhenUpdatingLocation() throws Exception {
		String body = "{\"latitude\":-23.5505,\"longitude\":-46.6333}";

		assertProblem(put(RESTAURANTS + "/" + UUID.randomUUID() + "/pickup-location", body), 404);
		assertProblem(put(RESTAURANTS + "/invalid/pickup-location", body), 400);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM restaurants", Integer.class)).isZero();
	}

	@Test
	void ignoresClientSuppliedIdAndActiveFlag() throws Exception {
		UUID suppliedId = UUID.randomUUID();
		Response response = post("{\"name\":\"Cantina\",\"id\":\"" + suppliedId + "\",\"active\":false}");

		assertThat(response.status()).isEqualTo(201);
		assertThat(UUID.fromString(response.body().path("id").asString())).isNotEqualTo(suppliedId);
		assertThat(response.body().path("active").asBoolean()).isTrue();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM restaurants WHERE id = ?", Integer.class, suppliedId))
				.isZero();
	}

	@Test
	void acceptsANameAtTheMaximumLengthAfterRemovingSurroundingWhitespace() throws Exception {
		Response response = post(objectMapper.writeValueAsString(Map.of("name", "  " + "a".repeat(120) + "  ")));

		assertThat(response.status()).isEqualTo(201);
		assertThat(response.body().path("name").asString()).hasSize(120);
	}

	@Test
	void findsAPersistedRestaurantById() throws Exception {
		Response created = post("{\"name\":\"Pizzaria Central\"}");
		assertThat(created.status()).isEqualTo(201);

		Response response = get(RESTAURANTS + "/" + created.body().path("id").asString());

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
		assertThat(ping.body().path("service").asString()).isEqualTo("catalog-service");
		assertThat(ping.body().path("status").asString()).isEqualTo("ok");

		Response health = get("/actuator/health");
		assertThat(health.status()).isEqualTo(200);
		assertThat(health.body().path("status").asString()).isEqualTo("UP");
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
		return page.path("content").valueStream().map(restaurant -> restaurant.path("id").asString()).toList();
	}

	private void assertProblem(Response response, int expectedStatus) {
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

	private Response post(String body) throws Exception {
		return send(HttpRequest.newBuilder(uri(RESTAURANTS)).timeout(Duration.ofSeconds(10))
				.header("Content-Type", "application/json").header("Authorization", OPERATOR)
				.POST(HttpRequest.BodyPublishers.ofString(body)).build());
	}

	private Response put(String path, String body) throws Exception {
		return send(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10))
				.header("Content-Type", "application/json").header("Authorization", OPERATOR)
				.PUT(HttpRequest.BodyPublishers.ofString(body)).build());
	}

	private HttpRequest write(String method, String path, String body, String authorization) {
		var request = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10))
				.header("Content-Type", "application/json")
				.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
		if (authorization != null) {
			request.header("Authorization", authorization);
		}
		return request.build();
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
