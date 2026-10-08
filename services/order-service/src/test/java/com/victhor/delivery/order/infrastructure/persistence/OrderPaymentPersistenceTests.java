package com.victhor.delivery.order.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.OptimisticLockException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.victhor.delivery.order.application.OrderPaymentRepository;
import com.victhor.delivery.order.application.OrderRepository;
import com.victhor.delivery.order.application.PaymentInProgressException;
import com.victhor.delivery.order.domain.DeliveryDestination;
import com.victhor.delivery.order.domain.IdempotencyKey;
import com.victhor.delivery.order.domain.Order;
import com.victhor.delivery.order.domain.OrderItem;
import com.victhor.delivery.order.domain.OrderPayment;
import com.victhor.delivery.order.domain.OrderPaymentStatus;
import com.victhor.delivery.order.domain.OrderPricing;
import com.victhor.delivery.order.domain.OrderStateConflictException;
import com.victhor.delivery.order.domain.OrderStatus;
import com.victhor.delivery.order.domain.SimulatedPaymentMethod;

@ActiveProfiles("test")
@SpringBootTest(properties = "ORDER_DB_PASSWORD=testcontainers-only")
@Testcontainers
class OrderPaymentPersistenceTests {

    private static final Instant CREATED = Instant.parse("2026-10-07T12:00:00Z");
    private static final IdempotencyKey KEY = new IdempotencyKey("checkout-0001");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private OrderRepository orders;

    @Autowired
    private OrderPaymentRepository payments;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManagerFactory entityManagers;

    private Order order;

    @BeforeEach
    void createOrder() {
        jdbc.update("DELETE FROM order_payments");
        jdbc.update("DELETE FROM orders");
        order = orders.save(Order.create(UUID.randomUUID(), UUID.randomUUID(),
                new DeliveryDestination("Rua das Flores, 42", -23.55, -46.63),
                new OrderPricing(List.of(new OrderItem(UUID.randomUUID(), "Lasagna", 2, new BigDecimal("32.50")))),
                CREATED));
    }

    @Test
    void recordsThePendingIntentAndMarksTheOrderInOneTransaction() {
        OrderPayment pending = payments.start(intent(KEY, SimulatedPaymentMethod.APPROVED_CARD));

        assertThat(pending.isPending()).isTrue();
        assertThat(pending.amount()).isEqualByComparingTo("65.00");
        assertThat(payments.find(order.id(), KEY)).contains(pending);
        Order awaiting = orders.findById(order.id()).orElseThrow();
        assertThat(awaiting.paymentRequestedAt()).isEqualTo(pending.requestedAt());
        assertThat(awaiting.status()).isEqualTo(OrderStatus.CREATED);
        assertThat(payments.start(intent(KEY, SimulatedPaymentMethod.DECLINED_CARD))).isEqualTo(pending);
        assertThatThrownBy(() -> payments.start(intent(new IdempotencyKey("checkout-0002"),
                SimulatedPaymentMethod.APPROVED_CARD))).isInstanceOf(PaymentInProgressException.class);
        assertThatThrownBy(() -> orders.cancel(order.id(), CREATED.plusSeconds(20)))
                .isInstanceOf(OrderStateConflictException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_payments", Integer.class)).isEqualTo(1);
    }

    @Test
    void approvalConfirmsTheOrderWithThePaymentAndIsAppliedOnce() {
        OrderPayment pending = payments.start(intent(KEY, SimulatedPaymentMethod.APPROVED_CARD));
        UUID paymentId = UUID.randomUUID();
        Instant approvedAt = CREATED.plusSeconds(15);

        OrderPayment approved = payments.complete(pending.approve(paymentId, approvedAt));

        assertThat(approved.status()).isEqualTo(OrderPaymentStatus.APPROVED);
        assertThat(payments.find(order.id(), KEY)).contains(approved);
        Order paid = orders.findById(order.id()).orElseThrow();
        assertThat(paid.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(paid.paymentId()).isEqualTo(paymentId);
        assertThat(paid.confirmedAt()).isEqualTo(approvedAt);
        assertThat(paid.paymentRequestedAt()).isNull();
        assertThat(payments.complete(pending.reject(CREATED.plusSeconds(30)))).isEqualTo(approved);
        assertThat(payments.start(intent(KEY, SimulatedPaymentMethod.APPROVED_CARD))).isEqualTo(approved);
        assertThatThrownBy(() -> payments.start(intent(new IdempotencyKey("checkout-0002"),
                SimulatedPaymentMethod.APPROVED_CARD))).isInstanceOf(OrderStateConflictException.class);
        assertThatThrownBy(() -> orders.cancel(order.id(), CREATED.plusSeconds(30)))
                .isInstanceOf(OrderStateConflictException.class);
    }

    @Test
    void declineReleasesTheOrderForAnotherAttemptOrCancellation() {
        OrderPayment pending = payments.start(intent(KEY, SimulatedPaymentMethod.DECLINED_CARD));
        OrderPayment declined = payments.complete(
                pending.decline(UUID.randomUUID(), "CARD_DECLINED", CREATED.plusSeconds(15)));

        assertThat(declined.declineReason()).isEqualTo("CARD_DECLINED");
        assertThat(orders.findById(order.id())).contains(order);
        var second = new IdempotencyKey("checkout-0002");
        payments.complete(payments.start(intent(second, SimulatedPaymentMethod.INSUFFICIENT_FUNDS_CARD))
                .reject(CREATED.plusSeconds(20)));
        assertThat(payments.find(order.id(), second).orElseThrow().status()).isEqualTo(OrderPaymentStatus.REJECTED);
        assertThat(orders.cancel(order.id(), CREATED.plusSeconds(30)).orElseThrow().status())
                .isEqualTo(OrderStatus.CANCELLED);
        assertThatThrownBy(() -> payments.start(intent(new IdempotencyKey("checkout-0003"),
                SimulatedPaymentMethod.APPROVED_CARD))).isInstanceOf(OrderStateConflictException.class);
    }

    @Test
    void aCancellationThatReadTheOrderBeforeThePaymentIntentCannotCommit() {
        try (var cancellation = entityManagers.createEntityManager()) {
            cancellation.getTransaction().begin();
            try {
                var stale = cancellation.find(OrderEntity.class, order.id());
                payments.start(intent(KEY, SimulatedPaymentMethod.APPROVED_CARD));
                stale.applyState(stale.toDomain().cancel(CREATED.plusSeconds(20)));
                assertThatThrownBy(cancellation::flush).isInstanceOf(OptimisticLockException.class);
            } finally {
                if (cancellation.getTransaction().isActive()) {
                    cancellation.getTransaction().rollback();
                }
            }
        }
        Order stored = orders.findById(order.id()).orElseThrow();
        assertThat(stored.status()).isEqualTo(OrderStatus.CREATED);
        assertThat(stored.paymentRequestedAt()).isNotNull();
    }

    @Test
    void aPaymentIntentCannotStartOnAnOrderCancelledMeanwhile() {
        orders.cancel(order.id(), CREATED.plusSeconds(10));

        assertThatThrownBy(() -> payments.start(intent(KEY, SimulatedPaymentMethod.APPROVED_CARD)))
                .isInstanceOf(OrderStateConflictException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_payments", Integer.class)).isZero();
    }

    @Test
    void databaseAllowsOnlyOneOpenIntentPerOrder() {
        payments.start(intent(KEY, SimulatedPaymentMethod.APPROVED_CARD));

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO order_payments (order_id, idempotency_key, method, amount, status, requested_at)
                SELECT order_id, 'checkout-0002', method, amount, status, requested_at FROM order_payments
                """)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "idempotency_key = 'short'", "method = '4111111111111111'", "amount = 0",
            "status = 'UNKNOWN'", "status = 'APPROVED'", "payment_id = gen_random_uuid()",
            "decline_reason = 'CARD_DECLINED'", "completed_at = requested_at - INTERVAL '1 second'"
    })
    void databaseRejectsInvalidIntentsEvenOutsideTheApplication(String assignment) {
        OrderPayment pending = payments.start(intent(KEY, SimulatedPaymentMethod.APPROVED_CARD));

        assertThatThrownBy(() -> jdbc.update("UPDATE order_payments SET " + assignment + " WHERE order_id = ?",
                order.id())).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(payments.find(order.id(), KEY)).contains(pending);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "payment_requested_at = created_at - INTERVAL '1 second'", "payment_id = gen_random_uuid()",
            "status = 'CANCELLED', cancelled_at = created_at, updated_at = created_at"
    })
    void databaseRejectsOrderPaymentStateThatContradictsTheLifecycle(String assignment) {
        payments.start(intent(KEY, SimulatedPaymentMethod.APPROVED_CARD));

        assertThatThrownBy(() -> jdbc.update("UPDATE orders SET " + assignment + " WHERE id = ?", order.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private OrderPayment intent(IdempotencyKey key, SimulatedPaymentMethod method) {
        return OrderPayment.request(order, key, method, CREATED.plusSeconds(10));
    }
}
