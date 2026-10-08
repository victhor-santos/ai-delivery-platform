package com.victhor.delivery.order.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.victhor.delivery.order.application.OrderNotFoundException;
import com.victhor.delivery.order.application.CatalogSelectionConflictException;
import com.victhor.delivery.order.application.RemoteServiceUnavailableException;
import com.victhor.delivery.order.application.DeliveryIntegrationConflictException;
import com.victhor.delivery.order.application.IdempotencyKeyReusedException;
import com.victhor.delivery.order.application.PaymentInProgressException;
import com.victhor.delivery.order.application.PaymentRejectedException;
import com.victhor.delivery.order.domain.OrderStateConflictException;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final String INTERNAL_ERROR_DETAIL = "Não foi possível processar a requisição.";

    @ExceptionHandler(OrderNotFoundException.class)
    ProblemDetail handleNotFound(OrderNotFoundException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Pedido não encontrado.");
    }

    @ExceptionHandler(OrderStateConflictException.class)
    ProblemDetail handleStateConflict(OrderStateConflictException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "A operação não é permitida no estado atual do pedido.");
    }

    @ExceptionHandler(DeliveryIntegrationConflictException.class)
    ProblemDetail handleDeliveryConflict(DeliveryIntegrationConflictException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "O restaurante ou os dados da entrega não permitem esta solicitação.");
    }

    @ExceptionHandler(PaymentInProgressException.class)
    ProblemDetail handlePaymentInProgress(PaymentInProgressException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "Outro pagamento deste pedido está em andamento. Repita-o com a mesma Idempotency-Key.");
    }

    @ExceptionHandler(PaymentRejectedException.class)
    ProblemDetail handlePaymentRejected(PaymentRejectedException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "O serviço de pagamentos não aceitou esta tentativa; nenhum valor foi cobrado.");
    }

    @ExceptionHandler(IdempotencyKeyReusedException.class)
    ProblemDetail handleIdempotencyKeyReused(IdempotencyKeyReusedException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT,
                "A Idempotency-Key já foi usada neste pedido com outro método de pagamento.");
    }

    @ExceptionHandler(CatalogSelectionConflictException.class)
    ProblemDetail handleCatalogConflict(CatalogSelectionConflictException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "O restaurante ou os itens informados não estão disponíveis para este pedido.");
    }

    @ExceptionHandler(RemoteServiceUnavailableException.class)
    ProblemDetail handleRemoteServiceUnavailable(RemoteServiceUnavailableException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "Não foi possível consultar um serviço necessário. Tente novamente mais tarde.");
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ProblemDetail handleConcurrentUpdate(OptimisticLockingFailureException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "O pedido foi alterado por outra requisição. Consulte o estado atual antes de tentar novamente.");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleInvalidInput(IllegalArgumentException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Dados do pedido inválidos.");
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpectedError(Exception exception) {
        log.error("Unexpected error processing order request", exception);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, INTERNAL_ERROR_DETAIL);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String detail = status.is5xxServerError() ? INTERNAL_ERROR_DETAIL : "Requisição inválida.";
        return super.handleExceptionInternal(exception, ProblemDetail.forStatusAndDetail(status, detail),
                headers, status, request);
    }
}
