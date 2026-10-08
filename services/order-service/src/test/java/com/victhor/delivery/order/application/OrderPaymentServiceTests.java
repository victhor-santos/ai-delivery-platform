package com.victhor.delivery.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.victhor.delivery.order.application.PaymentGateway.PaymentOutcome;
import com.victhor.delivery.order.domain.DeliveryDestination;
import com.victhor.delivery.order.domain.IdempotencyKey;
import com.victhor.delivery.order.domain.Order;
import com.victhor.delivery.order.domain.OrderItem;
import com.victhor.delivery.order.domain.OrderPayment;
import com.victhor.delivery.order.domain.OrderPaymentStatus;
import com.victhor.delivery.order.domain.OrderPricing;
import com.victhor.delivery.order.domain.SimulatedPaymentMethod;

@ExtendWith(MockitoExtension.class)
class OrderPaymentServiceTests {

    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00.123456789Z");
    private static final Instant TRUNCATED_NOW = Instant.parse("2026-10-07T12:00:00.123456Z");
    private static final UUID CUSTOMER_ID = UUID.randomUUID();
    private static final UUID PAYMENT_ID = UUID.randomUUID();
    private static final IdempotencyKey KEY = new IdempotencyKey("checkout-0001");
    private static final String TOKEN = "access-token";
    private static final SimulatedPaymentMethod APPROVED = SimulatedPaymentMethod.APPROVED_CARD;

    @Mock
    private OrderRepository orders;

    @Mock
    private OrderPaymentRepository payments;

    @Mock
    private PaymentGateway gateway;

    private OrderPaymentService service;
    private Order order;
    private OrderPayment pending;

    @BeforeEach
    void setUp() {
        service = new OrderPaymentService(orders, payments, gateway, Clock.fixed(NOW, ZoneOffset.UTC));
        order = Order.create(CUSTOMER_ID, UUID.randomUUID(), new DeliveryDestination("Rua Central", 0, 0),
                new OrderPricing(List.of(new OrderItem(UUID.randomUUID(), "Prato", 2, new BigDecimal("25.90")))),
                NOW.minusSeconds(60));
        pending = OrderPayment.request(order, KEY, APPROVED, TRUNCATED_NOW);
        when(orders.findById(order.id())).thenReturn(Optional.of(order));
    }

    @Test
    void recordsTheIntentBeforeChargingAndSettlesTheApproval() {
        when(payments.find(order.id(), KEY)).thenReturn(Optional.empty());
        when(payments.start(pending)).thenReturn(pending);
        when(gateway.charge(pending, TOKEN)).thenReturn(new PaymentOutcome(PAYMENT_ID, true, null));
        var approved = pending.approve(PAYMENT_ID, TRUNCATED_NOW);
        when(payments.complete(approved)).thenReturn(approved);

        assertThat(service.pay(order.id(), CUSTOMER_ID, KEY, APPROVED, TOKEN)).isEqualTo(approved);
        var calls = org.mockito.Mockito.inOrder(payments, gateway);
        calls.verify(payments).start(pending);
        calls.verify(gateway).charge(pending, TOKEN);
        calls.verify(payments).complete(approved);
    }

    @Test
    void settlesADeclineWithItsReason() {
        when(payments.find(order.id(), KEY)).thenReturn(Optional.of(pending));
        when(gateway.charge(pending, TOKEN)).thenReturn(new PaymentOutcome(PAYMENT_ID, false, "CARD_DECLINED"));
        var declined = pending.decline(PAYMENT_ID, "CARD_DECLINED", TRUNCATED_NOW);
        when(payments.complete(declined)).thenReturn(declined);

        assertThat(service.pay(order.id(), CUSTOMER_ID, KEY, APPROVED, TOKEN).status())
                .isEqualTo(OrderPaymentStatus.DECLINED);
        verify(payments, never()).start(any());
    }

    @Test
    void repeatsASettledIntentWithoutCallingPayment() {
        var approved = pending.approve(PAYMENT_ID, TRUNCATED_NOW);
        when(payments.find(order.id(), KEY)).thenReturn(Optional.of(approved));

        assertThat(service.pay(order.id(), CUSTOMER_ID, KEY, APPROVED, TOKEN)).isEqualTo(approved);
        verifyNoInteractions(gateway);
        verify(payments, never()).complete(any());
    }

    @Test
    void rejectsAKeyReusedWithAnotherMethodWithoutCallingPayment() {
        when(payments.find(order.id(), KEY)).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> service.pay(order.id(), CUSTOMER_ID, KEY, SimulatedPaymentMethod.DECLINED_CARD, TOKEN))
                .isInstanceOf(IdempotencyKeyReusedException.class);
        verifyNoInteractions(gateway);
    }

    @Test
    void keepsTheIntentPendingWhenTheOutcomeIsUnknown() {
        when(payments.find(order.id(), KEY)).thenReturn(Optional.of(pending));
        when(gateway.charge(pending, TOKEN)).thenThrow(new RemoteServiceUnavailableException());

        assertThatThrownBy(() -> service.pay(order.id(), CUSTOMER_ID, KEY, APPROVED, TOKEN))
                .isInstanceOf(RemoteServiceUnavailableException.class);
        verify(payments, never()).complete(any());
    }

    @Test
    void releasesTheOrderWhenPaymentRefusesWithoutCharging() {
        when(payments.find(order.id(), KEY)).thenReturn(Optional.of(pending));
        when(gateway.charge(pending, TOKEN)).thenThrow(new PaymentRejectedException(false));

        assertThatThrownBy(() -> service.pay(order.id(), CUSTOMER_ID, KEY, APPROVED, TOKEN))
                .isInstanceOf(PaymentRejectedException.class);
        verify(payments).complete(pending.reject(TRUNCATED_NOW));
        verify(gateway, never()).findApproved(any(), any());
    }

    @Test
    void adoptsAnApprovalMadeUnderAnotherKeyInsteadOfLeavingTheOrderOpen() {
        when(payments.find(order.id(), KEY)).thenReturn(Optional.of(pending));
        when(gateway.charge(pending, TOKEN)).thenThrow(new PaymentRejectedException(true));
        when(gateway.findApproved(pending, TOKEN)).thenReturn(Optional.of(new PaymentOutcome(PAYMENT_ID, true, null)));
        var approved = pending.approve(PAYMENT_ID, TRUNCATED_NOW);
        when(payments.complete(approved)).thenReturn(approved);

        assertThat(service.pay(order.id(), CUSTOMER_ID, KEY, APPROVED, TOKEN)).isEqualTo(approved);
    }

    @Test
    void releasesTheOrderWhenAConflictIsNotBackedByAnApproval() {
        when(payments.find(order.id(), KEY)).thenReturn(Optional.of(pending));
        when(gateway.charge(pending, TOKEN)).thenThrow(new PaymentRejectedException(true));
        when(gateway.findApproved(pending, TOKEN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.pay(order.id(), CUSTOMER_ID, KEY, APPROVED, TOKEN))
                .isInstanceOf(PaymentRejectedException.class);
        verify(payments).complete(pending.reject(TRUNCATED_NOW));
    }

    @Test
    void repeatsARefusalUnderTheSameKey() {
        when(payments.find(order.id(), KEY)).thenReturn(Optional.of(pending.reject(TRUNCATED_NOW)));

        assertThatThrownBy(() -> service.pay(order.id(), CUSTOMER_ID, KEY, APPROVED, TOKEN))
                .isInstanceOf(PaymentRejectedException.class);
        verifyNoInteractions(gateway);
    }

    @Test
    void hidesOrdersOfOtherCustomersWithoutRecordingAnything() {
        assertThatThrownBy(() -> service.pay(order.id(), UUID.randomUUID(), KEY, APPROVED, TOKEN))
                .isInstanceOf(OrderNotFoundException.class);
        verifyNoInteractions(payments, gateway);
    }
}
