package com.victhor.delivery.delivery.api;

import java.sql.SQLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
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

import com.victhor.delivery.delivery.application.CourierNotFoundException;
import com.victhor.delivery.delivery.application.DeliveryNotFoundException;
import com.victhor.delivery.delivery.domain.DeliveryStateConflictException;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final String INTERNAL_ERROR_DETAIL = "Não foi possível processar a requisição.";

    @ExceptionHandler(DeliveryNotFoundException.class)
    ProblemDetail handleDeliveryNotFound(DeliveryNotFoundException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Entrega não encontrada.");
    }

    @ExceptionHandler(CourierNotFoundException.class)
    ProblemDetail handleCourierNotFound(CourierNotFoundException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Entregador não encontrado.");
    }

    @ExceptionHandler(DeliveryStateConflictException.class)
    ProblemDetail handleStateConflict(DeliveryStateConflictException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "A operação não é permitida no estado atual da entrega ou para este entregador.");
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ProblemDetail handleConcurrentUpdate(OptimisticLockingFailureException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "A entrega foi alterada por outra requisição. Consulte o estado atual antes de tentar novamente.");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail handleIntegrityViolation(DataIntegrityViolationException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && "23505".equals(sql.getSQLState())) {
                return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                        "Já existe uma entrega para este pedido ou o entregador possui uma entrega em andamento.");
            }
        }
        return handleUnexpectedError(exception);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleInvalidInput(IllegalArgumentException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Dados da entrega ou do entregador inválidos.");
    }

    @ExceptionHandler(InvalidDataAccessApiUsageException.class)
    ProblemDetail handleInvalidRepositoryUsage(InvalidDataAccessApiUsageException exception) {
        if (exception.getMostSpecificCause() instanceof IllegalArgumentException invalidInput) {
            return handleInvalidInput(invalidInput);
        }
        return handleUnexpectedError(exception);
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpectedError(Exception exception) {
        log.error("Unexpected error processing delivery request", exception);
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
