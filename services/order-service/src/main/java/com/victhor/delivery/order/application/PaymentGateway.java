package com.victhor.delivery.order.application;

import java.util.Optional;
import java.util.UUID;

import com.victhor.delivery.order.domain.OrderPayment;

/** Port to the Payment Service. Calls carry the customer's access token, since Payment acts on their behalf. */
public interface PaymentGateway {

    /**
     * Submits the pending intent under its idempotency key.
     *
     * @throws PaymentRejectedException if Payment refused the request without charging
     * @throws RemoteServiceUnavailableException if the outcome is unknown
     */
    PaymentOutcome charge(OrderPayment pending, String accessToken);

    /** Looks for an approved attempt for the order, for example one made under another key. */
    Optional<PaymentOutcome> findApproved(OrderPayment pending, String accessToken);

    record PaymentOutcome(UUID paymentId, boolean approved, String declineReason) {
    }
}
