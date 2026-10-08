package com.victhor.delivery.payment.application;

import java.math.BigDecimal;
import java.util.UUID;

/** Port to the Order Service, called with the customer's access token so only their own orders are visible. */
public interface OrderLookup {

    /**
     * @throws PaymentOrderNotFoundException if the order does not exist or belongs to another customer
     * @throws RemoteServiceUnavailableException if the order could not be read
     */
    OrderSnapshot findById(UUID orderId, String accessToken);

    record OrderSnapshot(String status, BigDecimal total, boolean awaitingPayment) {

        /** Only an order whose payment Order has started can be charged, and only for its whole total. */
        public boolean accepts(BigDecimal amount) {
            return "CREATED".equals(status) && awaitingPayment && total != null && total.compareTo(amount) == 0;
        }
    }
}
