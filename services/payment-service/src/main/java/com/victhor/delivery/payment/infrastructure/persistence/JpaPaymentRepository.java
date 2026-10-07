package com.victhor.delivery.payment.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.victhor.delivery.payment.application.IdempotencyKeyAlreadyUsedException;
import com.victhor.delivery.payment.application.OrderAlreadyPaidException;
import com.victhor.delivery.payment.application.PaymentAttemptPage;
import com.victhor.delivery.payment.application.PaymentRepository;
import com.victhor.delivery.payment.domain.IdempotencyKey;
import com.victhor.delivery.payment.domain.PaymentAttempt;
import com.victhor.delivery.payment.domain.PaymentStatus;

@Repository
@Transactional(readOnly = true)
public class JpaPaymentRepository implements PaymentRepository {

    private static final String IDEMPOTENCY_KEY_CONSTRAINT = "payment_attempts_idempotency_key_unique";
    private static final String ONE_APPROVAL_CONSTRAINT = "payment_attempts_one_approval_per_order";
    private static final Sort ATTEMPT_ORDER = Sort.by("createdAt", "id");

    private final SpringDataPaymentAttemptRepository repository;

    public JpaPaymentRepository(SpringDataPaymentAttemptRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public PaymentAttempt save(PaymentAttempt attempt) {
        try {
            return repository.saveAndFlush(PaymentAttemptEntity.fromDomain(attempt)).toDomain();
        } catch (DataIntegrityViolationException exception) {
            String constraint = violatedConstraint(exception);
            if (IDEMPOTENCY_KEY_CONSTRAINT.equals(constraint)) {
                throw new IdempotencyKeyAlreadyUsedException();
            }
            if (ONE_APPROVAL_CONSTRAINT.equals(constraint)) {
                throw new OrderAlreadyPaidException();
            }
            throw exception;
        }
    }

    @Override
    public Optional<PaymentAttempt> findById(UUID id) {
        return repository.findById(id).map(PaymentAttemptEntity::toDomain);
    }

    @Override
    public Optional<PaymentAttempt> findByIdempotencyKey(UUID customerId, IdempotencyKey key) {
        return repository.findByCustomerIdAndIdempotencyKey(customerId, key.value())
                .map(PaymentAttemptEntity::toDomain);
    }

    @Override
    public boolean hasApprovedPayment(UUID customerId, UUID orderId) {
        return repository.existsByCustomerIdAndOrderIdAndStatus(customerId, orderId, PaymentStatus.APPROVED);
    }

    @Override
    public PaymentAttemptPage findByOrder(UUID customerId, UUID orderId, int page, int size) {
        var result = repository.findByCustomerIdAndOrderId(customerId, orderId,
                PageRequest.of(page, size, ATTEMPT_ORDER));
        return new PaymentAttemptPage(result.map(PaymentAttemptEntity::toDomain).getContent(), page, size,
                result.getTotalElements());
    }

    private static String violatedConstraint(DataIntegrityViolationException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                return violation.getConstraintName();
            }
        }
        return null;
    }
}
