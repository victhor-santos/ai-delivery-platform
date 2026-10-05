package com.victhor.delivery.catalog.api;

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

import com.victhor.delivery.catalog.application.RestaurantNotFoundException;
import com.victhor.delivery.catalog.application.MenuItemNotFoundException;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final String INTERNAL_ERROR_DETAIL = "Não foi possível processar a requisição.";

    @ExceptionHandler(RestaurantNotFoundException.class)
    ProblemDetail handleNotFound(RestaurantNotFoundException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Restaurante não encontrado.");
    }

    @ExceptionHandler(MenuItemNotFoundException.class)
    ProblemDetail handleMenuItemNotFound(MenuItemNotFoundException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Item do cardápio não encontrado.");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleInvalidInput(IllegalArgumentException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Entrada inválida. Confira o corpo e os parâmetros da requisição.");
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpectedError(Exception exception) {
        log.error("Unexpected error processing catalog request", exception);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, INTERNAL_ERROR_DETAIL);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String detail = status.is5xxServerError()
                ? INTERNAL_ERROR_DETAIL
                : "Requisição inválida. Confira o endereço, o corpo e os parâmetros enviados.";
        return super.handleExceptionInternal(exception, ProblemDetail.forStatusAndDetail(status, detail),
                headers, status, request);
    }
}
