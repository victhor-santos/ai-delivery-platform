package com.victhor.delivery.user.api;

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

import com.victhor.delivery.user.application.EmailAlreadyRegisteredException;
import com.victhor.delivery.user.application.InvalidCredentialsException;
import com.victhor.delivery.user.application.TooManyLoginAttemptsException;
import com.victhor.delivery.user.application.UserAddressNotFoundException;
import com.victhor.delivery.user.application.UserNotFoundException;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final String INTERNAL_ERROR_DETAIL = "Não foi possível processar a requisição.";

    @ExceptionHandler(InvalidCredentialsException.class)
    ResponseEntity<ProblemDetail> handleInvalidCredentials(InvalidCredentialsException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "E-mail ou senha inválidos."));
    }

    @ExceptionHandler(TooManyLoginAttemptsException.class)
    ResponseEntity<ProblemDetail> handleTooManyLoginAttempts(TooManyLoginAttemptsException exception) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(exception.retryAfterSeconds()))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
                        "Muitas tentativas de login. Tente novamente mais tarde."));
    }

    @ExceptionHandler(UserNotFoundException.class)
    ProblemDetail handleUserNotFound(UserNotFoundException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Usuário não encontrado.");
    }

    @ExceptionHandler(UserAddressNotFoundException.class)
    ProblemDetail handleAddressNotFound(UserAddressNotFoundException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Endereço não encontrado.");
    }

    @ExceptionHandler(EmailAlreadyRegisteredException.class)
    ProblemDetail handleEmailConflict(EmailAlreadyRegisteredException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "E-mail já cadastrado.");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleInvalidInput(IllegalArgumentException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Entrada inválida. Confira o corpo e os parâmetros da requisição.");
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpectedError(Exception exception) {
        log.error("Unexpected error processing user request", exception);
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
