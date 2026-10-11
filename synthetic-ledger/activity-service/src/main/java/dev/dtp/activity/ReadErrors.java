package dev.dtp.activity;

import com.mongodb.MongoException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
class ReadErrors {
    @ExceptionHandler(ResponseStatusException.class) ProblemDetail missing(ResponseStatusException error) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Projection not found; it may not yet be available.");
    }
    @ExceptionHandler({IllegalArgumentException.class, org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    ProblemDetail invalid(Exception error) { return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid read query."); }
    @ExceptionHandler({MongoException.class, org.springframework.dao.DataAccessException.class})
    ProblemDetail unavailable(Exception error) { return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "Projection storage unavailable."); }
}
