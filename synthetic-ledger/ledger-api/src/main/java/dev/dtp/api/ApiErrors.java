package dev.dtp.api;

import dev.dtp.application.AccessDenied;
import dev.dtp.application.IdempotencyMismatch;
import dev.dtp.domain.RuleViolation;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
class ApiErrors {
    @ExceptionHandler(dev.dtp.application.DeliveryBusy.class)
    ProblemDetail deliveryBusy(dev.dtp.application.DeliveryBusy error) {
        return problem(HttpStatus.CONFLICT, "delivery.busy", "Canonical delivery has a live publishing lease.");
    }
    @ExceptionHandler(dev.dtp.application.LegacyUnavailable.class)
    ProblemDetail legacyUnavailable(dev.dtp.application.LegacyUnavailable error) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "legacy.unavailable", "Legacy metadata is unavailable.");
    }
    @ExceptionHandler(dev.dtp.application.QuotaExceeded.class)
    org.springframework.http.ResponseEntity<ProblemDetail> quota(dev.dtp.application.QuotaExceeded error) {
        return org.springframework.http.ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", Long.toString(error.retryAfterSeconds()))
                .body(problem(HttpStatus.TOO_MANY_REQUESTS, "quota.exceeded", error.getMessage()));
    }
    @ExceptionHandler(dev.dtp.application.QuotaUnavailable.class)
    ProblemDetail quotaUnavailable(dev.dtp.application.QuotaUnavailable error) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "quota.unavailable", "Mutation quota is unavailable; retry with the same idempotency key.");
    }
    @ExceptionHandler(AccessDenied.class)
    ProblemDetail hidden(AccessDenied error) { return problem(HttpStatus.NOT_FOUND, "resource.not_found", "Resource not found."); }
    @ExceptionHandler(IdempotencyMismatch.class)
    ProblemDetail mismatch(IdempotencyMismatch error) { return problem(HttpStatus.UNPROCESSABLE_CONTENT, "idempotency.mismatch", error.getMessage()); }
    @ExceptionHandler(RuleViolation.class)
    ProblemDetail rule(RuleViolation error) {
        var status = error.code().equals("funds.insufficient") || error.code().equals("account.inactive")
                ? HttpStatus.CONFLICT : HttpStatus.BAD_REQUEST;
        return problem(status, error.code(), error.getMessage());
    }
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MissingRequestHeaderException.class, MethodArgumentTypeMismatchException.class})
    ProblemDetail badRequest(Exception error) { return problem(HttpStatus.BAD_REQUEST, "request.invalid", "Invalid request."); }
    @ExceptionHandler(DataAccessException.class)
    ProblemDetail database(DataAccessException error) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "storage.unavailable", "Storage could not complete the operation; retry with the same idempotency key.");
    }
    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        var p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setTitle(status.getReasonPhrase()); p.setProperty("code", code);
        return p;
    }
}
