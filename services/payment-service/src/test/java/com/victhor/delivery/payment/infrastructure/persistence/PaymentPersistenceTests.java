package com.victhor.delivery.payment.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.victhor.delivery.payment.application.IdempotencyKeyAlreadyUsedException;
import com.victhor.delivery.payment.application.OrderAlreadyPaidException;
import com.victhor.delivery.payment.application.PaymentRepository;
import com.victhor.delivery.payment.application.PaymentResult;
import com.victhor.delivery.payment.application.OrderLookup;
import com.victhor.delivery.payment.application.PaymentService;
import com.victhor.delivery.payment.domain.IdempotencyKey;
import com.victhor.delivery.payment.domain.PaymentAttempt;
import com.victhor.delivery.payment.domain.SimulatedPaymentMethod;

@ActiveProfiles("test")
@SpringBootTest(properties = "PAYMENT_DB_PASSWORD=testcontainers-only")
@Testcontainers
class PaymentPersistenceTests {

    private static final String TOKEN = "access-token";

    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00.123456Z");
    private static final BigDecimal AMOUNT = new BigDecimal("59.80");
    private static final int CONCURRENT_REQUESTS = 8;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private PaymentRepository payments;

    @Autowired
    private PaymentService service;

    @MockitoBean
    private OrderLookup orders;

    @Autowired
    private JdbcTemplate jdbc;

    private final UUID customer = UUID.randomUUID();
    private final UUID order = UUID.randomUUID();

    @BeforeEach
    void clearAttempts() {
        jdbc.update("DELETE FROM payment_attempts");
        when(orders.findById(order, TOKEN)).thenReturn(new OrderLookup.OrderSnapshot("CREATED", AMOUNT, true));
    }

    @Test
    void storesAndRestoresEveryFieldWithExactCents() {
        var attempt = attempt(customer, order, "checkout-0001", SimulatedPaymentMethod.INSUFFICIENT_FUNDS_CARD);

        assertThat(payments.save(attempt)).isEqualTo(attempt);
        assertThat(payments.findById(attempt.id())).contains(attempt);
        assertThat(payments.findByIdempotencyKey(customer, new IdempotencyKey("checkout-0001"))).contains(attempt);
        assertThat(jdbc.queryForObject("SELECT amount::text FROM payment_attempts", String.class)).isEqualTo("59.80");
        assertThat(jdbc.queryForObject("SELECT method || ' ' || status || ' ' || decline_reason FROM payment_attempts",
                String.class)).isEqualTo("INSUFFICIENT_FUNDS_CARD DECLINED INSUFFICIENT_FUNDS");
    }

    @Test
    void scopesIdempotencyKeysAndApprovalsToTheCustomer() {
        payments.save(attempt(customer, order, "checkout-0001", SimulatedPaymentMethod.APPROVED_CARD));
        UUID other = UUID.randomUUID();

        payments.save(attempt(other, order, "checkout-0001", SimulatedPaymentMethod.APPROVED_CARD));

        assertThat(payments.hasApprovedPayment(customer, order)).isTrue();
        assertThat(payments.hasApprovedPayment(other, order)).isTrue();
        assertThat(payments.hasApprovedPayment(customer, UUID.randomUUID())).isFalse();
        assertThat(payments.findByIdempotencyKey(UUID.randomUUID(), new IdempotencyKey("checkout-0001"))).isEmpty();
    }

    @Test
    void translatesADuplicateKeyAndASecondApprovalWithoutStoringThem() {
        var first = payments.save(attempt(customer, order, "checkout-0001", SimulatedPaymentMethod.APPROVED_CARD));

        assertThatThrownBy(() -> payments.save(attempt(customer, UUID.randomUUID(), "checkout-0001",
                SimulatedPaymentMethod.DECLINED_CARD))).isInstanceOf(IdempotencyKeyAlreadyUsedException.class);
        assertThatThrownBy(() -> payments.save(attempt(customer, order, "checkout-0002",
                SimulatedPaymentMethod.APPROVED_CARD))).isInstanceOf(OrderAlreadyPaidException.class);
        payments.save(attempt(customer, order, "checkout-0003", SimulatedPaymentMethod.DECLINED_CARD));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_attempts", Integer.class)).isEqualTo(2);
        assertThat(payments.findById(first.id())).contains(first);
    }

    @Test
    void pagesTheCustomersAttemptsForAnOrderByCreationTimeAndId() {
        var expected = new ArrayList<PaymentAttempt>();
        for (int index = 0; index < 5; index++) {
            var method = index == 4 ? SimulatedPaymentMethod.APPROVED_CARD : SimulatedPaymentMethod.DECLINED_CARD;
            expected.add(payments.save(PaymentAttempt.process(customer, order, new IdempotencyKey("checkout-000" + index),
                    AMOUNT, method, NOW.plusSeconds(index % 2))));
        }
        payments.save(attempt(customer, UUID.randomUUID(), "other-order-1", SimulatedPaymentMethod.APPROVED_CARD));
        payments.save(attempt(UUID.randomUUID(), order, "other-customer", SimulatedPaymentMethod.APPROVED_CARD));
        expected.sort((left, right) -> left.createdAt().equals(right.createdAt())
                ? left.id().compareTo(right.id()) : left.createdAt().compareTo(right.createdAt()));

        var pages = Stream.of(0, 1, 2).map(page -> payments.findByOrder(customer, order, page, 2)).toList();

        assertThat(pages).allSatisfy(page -> assertThat(page.totalElements()).isEqualTo(5));
        assertThat(pages.get(0).totalPages()).isEqualTo(3);
        assertThat(pages.stream().flatMap(page -> page.items().stream()).map(PaymentAttempt::createdAt).toList())
                .isSorted();
        assertThat(pages.stream().flatMap(page -> page.items().stream()).toList())
                .containsExactlyInAnyOrderElementsOf(expected);
        assertThat(payments.findByOrder(customer, order, 3, 2).items()).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("inconsistentRows")
    void rejectsInconsistentRowsInTheDatabase(String key, String amount, String method, String status, String reason) {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO payment_attempts (id, customer_id, order_id, idempotency_key, amount, method, status,
                    decline_reason, created_at) VALUES (?, ?, ?, ?, ?::numeric, ?, ?, ?, ?)
                """, UUID.randomUUID(), customer, order, key, amount, method, status, reason, Timestamp.from(NOW)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    static Stream<Arguments> inconsistentRows() {
        return Stream.of(
                Arguments.of("short", "1.00", "APPROVED_CARD", "APPROVED", null),
                Arguments.of("with space key", "1.00", "APPROVED_CARD", "APPROVED", null),
                Arguments.of("checkout-0001", "0.00", "APPROVED_CARD", "APPROVED", null),
                Arguments.of("checkout-0001", "494999999950.51", "APPROVED_CARD", "APPROVED", null),
                Arguments.of("checkout-0001", "1.00", "DECLINED_CARD", "APPROVED", null),
                Arguments.of("checkout-0001", "1.00", "APPROVED_CARD", "APPROVED", "CARD_DECLINED"),
                Arguments.of("checkout-0001", "1.00", "INSUFFICIENT_FUNDS_CARD", "DECLINED", "CARD_DECLINED"),
                Arguments.of("checkout-0001", "1.00", "REAL_CARD", "APPROVED", null));
    }

    @Test
    void concurrentRequestsWithTheSameKeyStoreOneAttemptAndReturnIt() throws Exception {
        var key = new IdempotencyKey("checkout-race");
        var results = race(() -> service.attempt(customer, key, order, AMOUNT, SimulatedPaymentMethod.APPROVED_CARD,
                TOKEN));

        assertThat(results).allSatisfy(result -> assertThat(result).isInstanceOf(PaymentResult.class));
        assertThat(results.stream().map(result -> ((PaymentResult) result).attempt().id()).distinct()).hasSize(1);
        assertThat(results.stream().filter(result -> !((PaymentResult) result).replayed())).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_attempts", Integer.class)).isEqualTo(1);
    }

    @Test
    void concurrentApprovalsWithDifferentKeysChargeTheOrderOnce() throws Exception {
        var results = race(() -> service.attempt(customer, new IdempotencyKey("checkout-" + UUID.randomUUID()), order,
                AMOUNT, SimulatedPaymentMethod.APPROVED_CARD, TOKEN));

        assertThat(results.stream().filter(PaymentResult.class::isInstance)).hasSize(1);
        assertThat(results.stream().filter(OrderAlreadyPaidException.class::isInstance))
                .hasSize(CONCURRENT_REQUESTS - 1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_attempts WHERE status = 'APPROVED'",
                Integer.class)).isEqualTo(1);
    }

    private static List<Object> race(Callable<PaymentResult> request) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);
        try {
            var start = new CountDownLatch(1);
            var futures = new ArrayList<Future<Object>>();
            for (int index = 0; index < CONCURRENT_REQUESTS; index++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    try {
                        return request.call();
                    } catch (RuntimeException exception) {
                        return exception;
                    }
                }));
            }
            start.countDown();
            var results = new ArrayList<Object>();
            for (var future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private static PaymentAttempt attempt(UUID customerId, UUID orderId, String key, SimulatedPaymentMethod method) {
        return PaymentAttempt.process(customerId, orderId, new IdempotencyKey(key), AMOUNT, method,
                NOW.truncatedTo(ChronoUnit.MICROS));
    }
}
