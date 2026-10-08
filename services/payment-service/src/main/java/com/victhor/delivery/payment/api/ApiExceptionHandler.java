package com.victhor.delivery.payment.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.victhor.delivery.payment.application.IdempotencyKeyReusedException;
import com.victhor.delivery.payment.application.OrderAlreadyPaidException;
import com.victhor.delivery.payment.application.OrderNotPayableException;
import com.victhor.delivery.payment.application.PaymentNotFoundException;
import com.victhor.delivery.payment.application.PaymentOrderNotFoundException;
import com.victhor.delivery.payment.application.RemoteServiceUnavailableException;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final String INTERNAL_ERROR_DETAIL = "Não foi possível processar a requisição.";

    @ExceptionHandler(PaymentNotFoundException.class)
    ProblemDetail handleNotFound(PaymentNotFoundException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Pagamento não encontrado.");
    }

    @ExceptionHandler(OrderAlreadyPaidException.class)
    ProblemDetail handleAlreadyPaid(OrderAlreadyPaidException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "O pedido já possui um pagamento aprovado.");
    }

    @ExceptionHandler(PaymentOrderNotFoundException.class)
    ProblemDetail handleOrderNotFound(PaymentOrderNotFoundException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Pedido não encontrado.");
    }

    @ExceptionHandler(OrderNotPayableException.class)
    ProblemDetail handleOrderNotPayable(OrderNotPayableException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "O pedido não aguarda um pagamento com este valor. Pague-o por POST /api/orders/{id}/payment.");
    }

    @ExceptionHandler(RemoteServiceUnavailableException.class)
    ProblemDetail handleRemoteServiceUnavailable(RemoteServiceUnavailableException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "Não foi possível consultar um serviço necessário. Tente novamente mais tarde.");
    }

    /** Follows the IETF Idempotency-Key draft: reusing a key for another payload is 422, not a replay. */
    @ExceptionHandler(IdempotencyKeyReusedException.class)
    ProblemDetail handleReusedKey(IdempotencyKeyReusedException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT,
                "A chave de idempotência já foi usada para outro pagamento.");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleInvalidInput(IllegalArgumentException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Dados do pagamento inválidos.");
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpectedError(Exception exception) {
        log.error("Unexpected error processing payment request", exception);
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
