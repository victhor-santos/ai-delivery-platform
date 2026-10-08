package com.victhor.delivery.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.victhor.delivery.payment.domain.IdempotencyKey;
import com.victhor.delivery.payment.domain.PaymentAttempt;
import com.victhor.delivery.payment.domain.PaymentStatus;
import com.victhor.delivery.payment.domain.SimulatedPaymentMethod;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTests {

    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00.123456789Z");
    private static final Instant DATABASE_TIME = Instant.parse("2026-10-07T12:00:00.123456Z");
    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final UUID ORDER = UUID.randomUUID();
    private static final IdempotencyKey KEY = new IdempotencyKey("checkout-0001");
    private static final BigDecimal AMOUNT = new BigDecimal("59.80");
    private static final String TOKEN = "access-token";
    private static final OrderLookup.OrderSnapshot AWAITING = new OrderLookup.OrderSnapshot("CREATED", AMOUNT, true);

    @Mock
    private PaymentRepository payments;

    /** Lenient: replays and validation failures never reach Order, which some tests assert explicitly. */
    @Mock(strictness = Mock.Strictness.LENIENT)
    private OrderLookup orders;

    private PaymentService service;

    @BeforeEach
    void setUp() {
        service = new PaymentService(payments, orders, Clock.fixed(NOW, ZoneOffset.UTC));
        when(orders.findById(ORDER, TOKEN)).thenReturn(AWAITING);
    }

    @Test
    void storesANewSimulatedAttemptAtPostgresPrecision() {
        when(payments.findByIdempotencyKey(CUSTOMER, KEY)).thenReturn(Optional.empty());
        when(payments.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.attempt(CUSTOMER, KEY, ORDER, new BigDecimal("59.8"),
                SimulatedPaymentMethod.APPROVED_CARD, TOKEN);

        assertThat(result.replayed()).isFalse();
        assertThat(result.attempt().status()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(result.attempt().amount()).isEqualTo(AMOUNT);
        assertThat(result.attempt().createdAt()).isEqualTo(DATABASE_TIME);
        verify(payments).save(result.attempt());
    }

    @Test
    void storesDeclinedAttemptsWithoutBlockingANewKey() {
        when(payments.findByIdempotencyKey(CUSTOMER, KEY)).thenReturn(Optional.empty());
        when(payments.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.attempt(CUSTOMER, KEY, ORDER, AMOUNT, SimulatedPaymentMethod.INSUFFICIENT_FUNDS_CARD, TOKEN);

        assertThat(result.attempt().status()).isEqualTo(PaymentStatus.DECLINED);
        verify(payments).hasApprovedPayment(CUSTOMER, ORDER);
    }

    @Test
    void replaysTheStoredAttemptForTheSameKeyAndIntentWithoutProcessingAgain() {
        var stored = attempt(SimulatedPaymentMethod.DECLINED_CARD);
        when(payments.findByIdempotencyKey(CUSTOMER, KEY)).thenReturn(Optional.of(stored));

        var result = service.attempt(CUSTOMER, KEY, ORDER, new BigDecimal("59.8"), SimulatedPaymentMethod.DECLINED_CARD, TOKEN);

        assertThat(result.replayed()).isTrue();
        assertThat(result.attempt()).isSameAs(stored);
        verify(payments, never()).save(any());
        verify(payments, never()).hasApprovedPayment(any(), any());
        verifyNoInteractions(orders);
    }

    @ParameterizedTest
    @CsvSource({ "other-order, 59.80, sim-card-approved", "same-order, 59.81, sim-card-approved",
            "same-order, 59.80, sim-card-declined" })
    void rejectsAKeyReusedForAnotherIntent(String order, String amount, String method) {
        when(payments.findByIdempotencyKey(CUSTOMER, KEY))
                .thenReturn(Optional.of(attempt(SimulatedPaymentMethod.APPROVED_CARD)));
        UUID orderId = order.equals("same-order") ? ORDER : UUID.randomUUID();

        assertThatThrownBy(() -> service.attempt(CUSTOMER, KEY, orderId, new BigDecimal(amount),
                SimulatedPaymentMethod.fromCode(method), TOKEN)).isInstanceOf(IdempotencyKeyReusedException.class);
        verify(payments, never()).save(any());
    }

    @Test
    void refusesANewAttemptOnceTheCustomerPaidTheOrder() {
        when(payments.findByIdempotencyKey(CUSTOMER, KEY)).thenReturn(Optional.empty());
        when(payments.hasApprovedPayment(CUSTOMER, ORDER)).thenReturn(true);

        assertThatThrownBy(() -> service.attempt(CUSTOMER, KEY, ORDER, AMOUNT, SimulatedPaymentMethod.DECLINED_CARD, TOKEN))
                .isInstanceOf(OrderAlreadyPaidException.class);
        verify(payments, never()).save(any());
        verifyNoInteractions(orders);
    }

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = { "CREATED, 59.80, false", "CONFIRMED, 59.80, false",
            "CANCELLED, 59.80, false", "CREATED, 59.81, true", "CREATED, 59.79, true", "CREATED, null, true" })
    void chargesOnlyAnOrderAwaitingExactlyThisAmount(String status, BigDecimal total, boolean awaiting) {
        when(payments.findByIdempotencyKey(CUSTOMER, KEY)).thenReturn(Optional.empty());
        when(orders.findById(ORDER, TOKEN)).thenReturn(new OrderLookup.OrderSnapshot(status, total, awaiting));

        assertThatThrownBy(() -> service.attempt(CUSTOMER, KEY, ORDER, AMOUNT, SimulatedPaymentMethod.APPROVED_CARD,
                TOKEN)).isInstanceOf(OrderNotPayableException.class);
        verify(payments, never()).save(any());
    }

    @Test
    void chargesNothingWhenTheOrderIsMissingOrUnreadable() {
        when(payments.findByIdempotencyKey(CUSTOMER, KEY)).thenReturn(Optional.empty());
        when(orders.findById(ORDER, TOKEN)).thenThrow(new PaymentOrderNotFoundException())
                .thenThrow(new RemoteServiceUnavailableException());

        assertThatThrownBy(() -> service.attempt(CUSTOMER, KEY, ORDER, AMOUNT, SimulatedPaymentMethod.APPROVED_CARD,
                TOKEN)).isInstanceOf(PaymentOrderNotFoundException.class);
        assertThatThrownBy(() -> service.attempt(CUSTOMER, KEY, ORDER, AMOUNT, SimulatedPaymentMethod.APPROVED_CARD,
                TOKEN)).isInstanceOf(RemoteServiceUnavailableException.class);
        verify(payments, never()).save(any());
    }

    @Test
    void comparesTheOrderTotalIgnoringScale() {
        when(payments.findByIdempotencyKey(CUSTOMER, KEY)).thenReturn(Optional.empty());
        when(orders.findById(ORDER, TOKEN)).thenReturn(new OrderLookup.OrderSnapshot("CREATED",
                new BigDecimal("59.8"), true));
        when(payments.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(service.attempt(CUSTOMER, KEY, ORDER, AMOUNT, SimulatedPaymentMethod.APPROVED_CARD, TOKEN)
                .replayed()).isFalse();
    }

    @Test
    void propagatesAnApprovalThatLostTheRaceForTheOrder() {
        when(payments.findByIdempotencyKey(CUSTOMER, KEY)).thenReturn(Optional.empty());
        when(payments.save(any())).thenThrow(new OrderAlreadyPaidException());

        assertThatThrownBy(() -> service.attempt(CUSTOMER, KEY, ORDER, AMOUNT, SimulatedPaymentMethod.APPROVED_CARD, TOKEN))
                .isInstanceOf(OrderAlreadyPaidException.class);
    }

    @Test
    void returnsTheConcurrentWinnerWhenTheSameKeyIsStoredFirstByAnotherRequest() {
        var winner = attempt(SimulatedPaymentMethod.APPROVED_CARD);
        when(payments.findByIdempotencyKey(CUSTOMER, KEY)).thenReturn(Optional.empty(), Optional.of(winner));
        when(payments.save(any())).thenThrow(new IdempotencyKeyAlreadyUsedException());

        var result = service.attempt(CUSTOMER, KEY, ORDER, AMOUNT, SimulatedPaymentMethod.APPROVED_CARD, TOKEN);

        assertThat(result.replayed()).isTrue();
        assertThat(result.attempt()).isSameAs(winner);
    }

    @Test
    void replaysTheSameKeyWhenItsConcurrentApprovalIsSeenAsAPaidOrder() {
        var winner = attempt(SimulatedPaymentMethod.APPROVED_CARD);
        when(payments.findByIdempotencyKey(CUSTOMER, KEY)).thenReturn(Optional.empty(), Optional.of(winner));
        when(payments.hasApprovedPayment(CUSTOMER, ORDER)).thenReturn(true);

        var result = service.attempt(CUSTOMER, KEY, ORDER, AMOUNT, SimulatedPaymentMethod.APPROVED_CARD, TOKEN);

        assertThat(result.replayed()).isTrue();
        assertThat(result.attempt()).isSameAs(winner);
        verify(payments, never()).save(any());
    }

    @Test
    void rejectsAConcurrentWinnerWithAnotherIntent() {
        when(payments.findByIdempotencyKey(CUSTOMER, KEY))
                .thenReturn(Optional.empty(), Optional.of(attempt(SimulatedPaymentMethod.DECLINED_CARD)));
        when(payments.save(any())).thenThrow(new IdempotencyKeyAlreadyUsedException());

        assertThatThrownBy(() -> service.attempt(CUSTOMER, KEY, ORDER, AMOUNT, SimulatedPaymentMethod.APPROVED_CARD, TOKEN))
                .isInstanceOf(IdempotencyKeyReusedException.class);
    }

    @Test
    void validatesTheRequestBeforeTouchingTheRepository() {
        var method = SimulatedPaymentMethod.APPROVED_CARD;
        assertThatNullPointerException().isThrownBy(() -> service.attempt(null, KEY, ORDER, AMOUNT, method, TOKEN));
        assertThatNullPointerException().isThrownBy(() -> service.attempt(CUSTOMER, null, ORDER, AMOUNT, method, TOKEN));
        assertThatNullPointerException().isThrownBy(() -> service.attempt(CUSTOMER, KEY, null, AMOUNT, method, TOKEN));
        assertThatNullPointerException().isThrownBy(() -> service.attempt(CUSTOMER, KEY, ORDER, AMOUNT, null, TOKEN));
        assertThatNullPointerException().isThrownBy(() -> service.attempt(CUSTOMER, KEY, ORDER, AMOUNT, method, null));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.attempt(CUSTOMER, KEY, ORDER, new BigDecimal("0.001"), method, TOKEN));
        verifyNoInteractions(payments, orders);
    }

    @Test
    void reportsAnotherCustomersOrAMissingAttemptAsAbsent() {
        var stored = attempt(SimulatedPaymentMethod.APPROVED_CARD);
        when(payments.findById(stored.id())).thenReturn(Optional.of(stored));

        assertThat(service.findById(stored.id(), CUSTOMER)).isSameAs(stored);
        assertThatThrownBy(() -> service.findById(stored.id(), UUID.randomUUID()))
                .isInstanceOf(PaymentNotFoundException.class);
        assertThatThrownBy(() -> service.findById(UUID.randomUUID(), CUSTOMER))
                .isInstanceOf(PaymentNotFoundException.class);
    }

    @Test
    void listsTheCustomersAttemptsForAnOrderWithBoundedPages() {
        var page = new PaymentAttemptPage(List.of(attempt(SimulatedPaymentMethod.DECLINED_CARD)), 0, 20, 1);
        when(payments.findByOrder(CUSTOMER, ORDER, 0, 20)).thenReturn(page);

        assertThat(service.findByOrder(CUSTOMER, ORDER, 0, 20)).isSameAs(page);
        for (int[] invalid : new int[][] { { -1, 20 }, { 0, 0 }, { 0, 101 }, { Integer.MAX_VALUE, 2 } }) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> service.findByOrder(CUSTOMER, ORDER, invalid[0], invalid[1]));
        }
        verify(payments).findByOrder(CUSTOMER, ORDER, 0, 20);
        verifyNoMoreInteractions(payments);
    }

    private static PaymentAttempt attempt(SimulatedPaymentMethod method) {
        return PaymentAttempt.process(CUSTOMER, ORDER, KEY, AMOUNT, method, DATABASE_TIME);
    }
}
