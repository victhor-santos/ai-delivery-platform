package com.victhor.delivery.order.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.victhor.delivery.order.application.OrderNotFoundException;
import com.victhor.delivery.order.application.OrderPaymentRepository;
import com.victhor.delivery.order.application.PaymentInProgressException;
import com.victhor.delivery.order.domain.IdempotencyKey;
import com.victhor.delivery.order.domain.Order;
import com.victhor.delivery.order.domain.OrderPayment;
import com.victhor.delivery.order.domain.OrderPaymentStatus;

@Repository
@Transactional(readOnly = true)
public class JpaOrderPaymentRepository implements OrderPaymentRepository {

    private final SpringDataOrderRepository orders;
    private final SpringDataOrderPaymentRepository payments;

    public JpaOrderPaymentRepository(SpringDataOrderRepository orders, SpringDataOrderPaymentRepository payments) {
        this.orders = orders;
        this.payments = payments;
    }

    @Override
    public Optional<OrderPayment> find(UUID orderId, IdempotencyKey key) {
        return payments.findById(new OrderPaymentEntity.Key(orderId, key.value())).map(OrderPaymentEntity::toDomain);
    }

    @Override
    @Transactional
    public OrderPayment start(OrderPayment payment) {
        var entity = orders.findById(payment.orderId()).orElseThrow(OrderNotFoundException::new);
        var existing = find(payment.orderId(), payment.idempotencyKey());
        if (existing.isPresent()) {
            return existing.orElseThrow();
        }
        Order order = entity.toDomain();
        Order awaiting = order.requestPayment(payment.requestedAt());
        if (awaiting == order) {
            throw new PaymentInProgressException();
        }
        // The order version changes in this transaction, so a concurrent cancellation cannot also commit.
        entity.applyState(awaiting);
        orders.flush();
        return payments.saveAndFlush(OrderPaymentEntity.from(payment)).toDomain();
    }

    @Override
    @Transactional
    public OrderPayment complete(OrderPayment completed) {
        var entity = orders.findById(completed.orderId()).orElseThrow(OrderNotFoundException::new);
        var stored = payments.findById(new OrderPaymentEntity.Key(completed.orderId(), completed.idempotencyKey().value()))
                .orElseThrow(() -> new IllegalStateException("Payment intent was not recorded"));
        if (stored.toDomain().status() != OrderPaymentStatus.PENDING) {
            return stored.toDomain();
        }
        Order order = entity.toDomain();
        entity.applyState(completed.status() == OrderPaymentStatus.APPROVED
                ? order.confirmPayment(completed.paymentId(), completed.completedAt())
                : order.releasePayment());
        stored.applyOutcome(completed);
        orders.flush();
        return stored.toDomain();
    }
}
