package com.flowdesk.project.api;

import com.flowdesk.identity.application.ActorUnavailableException;
import com.flowdesk.project.application.ProjectNotFoundException;
import com.flowdesk.project.application.ProjectArchivedException;
import com.flowdesk.project.domain.InvalidProjectInputException;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice(assignableTypes = {ProjectController.class, ProjectCreateController.class, ProjectUpdateController.class, ProjectArchiveController.class})
@Profile("dev-actor")
public class ProjectErrors extends ResponseEntityExceptionHandler {
    @ExceptionHandler({com.flowdesk.project.application.ProjectVersionConflictException.class,
            org.springframework.dao.OptimisticLockingFailureException.class,
            jakarta.persistence.OptimisticLockException.class})
    ResponseEntity<Object> conflict(Exception exception, WebRequest request) {
        return handleExceptionInternal(exception, null, new HttpHeaders(), HttpStatus.CONFLICT, request);
    }

    @ExceptionHandler(com.flowdesk.project.application.ProjectForbiddenException.class)
    ResponseEntity<Object> forbidden(Exception exception, WebRequest request) {
        var body = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Project modification is unavailable.");
        body.setProperty("code", "PROJECT_FORBIDDEN");
        return new ResponseEntity<>(body, new HttpHeaders(), HttpStatus.FORBIDDEN);
    }

    @ExceptionHandler(ProjectArchivedException.class)
    ResponseEntity<Object> archived(Exception exception, WebRequest request) {
        var body=ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,"Archived projects cannot be modified.");
        body.setProperty("code","PROJECT_ARCHIVED");
        return new ResponseEntity<>(body,new HttpHeaders(),HttpStatus.CONFLICT);
    }

    @ExceptionHandler(InvalidProjectInputException.class)
    ResponseEntity<Object> invalidName(Exception exception, WebRequest request) {
        return handleExceptionInternal(exception, null, new HttpHeaders(), HttpStatus.BAD_REQUEST, request);
    }
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return super.handleExceptionInternal(exception, problem(status.value()), headers, status, request);
    }

    @ExceptionHandler({DataAccessResourceFailureException.class, CannotCreateTransactionException.class,
            QueryTimeoutException.class, TransientDataAccessResourceException.class, ActorUnavailableException.class})
    ResponseEntity<Object> unavailable(Exception exception, WebRequest request) {
        return handleExceptionInternal(exception, null, new HttpHeaders(), HttpStatus.SERVICE_UNAVAILABLE, request);
    }

    @ExceptionHandler(ProjectNotFoundException.class)
    ResponseEntity<Object> notFound(Exception exception, WebRequest request) {
        return handleExceptionInternal(exception, null, new HttpHeaders(), HttpStatus.NOT_FOUND, request);
    }

    @ExceptionHandler(JpaSystemException.class)
    ResponseEntity<Object> persistenceFailure(JpaSystemException exception, WebRequest request) {
        // An expired pooled connection may fail while Hibernate begins/ends the transaction,
        // before Spring can translate it to DataAccessResourceFailureException.
        boolean transactionFailure = false;
        boolean jdbcFailure = false;
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            transactionFailure |= cause instanceof org.hibernate.TransactionException;
            if (cause instanceof java.sql.SQLException sql) {
                String state = sql.getSQLState();
                jdbcFailure |= state == null || state.startsWith("08")
                        || java.util.Set.of("40001", "40P01", "57P01").contains(state);
            }
        }
        return transactionFailure && jdbcFailure ? unavailable(exception, request) : unexpected(exception, request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpected(Exception exception, WebRequest request) {
        // Log types only: exception messages may contain database/configuration information.
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            logger.error("Project request failure type: " + cause.getClass().getName());
        }
        return handleExceptionInternal(exception, null, new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR, request);
    }

    static ProblemDetail problem(int status) {
        String code = switch (status) {
            case 400 -> "INVALID_REQUEST";
            case 403 -> "DEV_ACCESS_DENIED";
            case 404 -> "PROJECT_NOT_FOUND";
            case 409 -> "PROJECT_VERSION_CONFLICT";
            case 413 -> "REQUEST_TOO_LARGE";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            case 503 -> "SERVICE_UNAVAILABLE";
            default -> status >= 500 ? "INTERNAL_ERROR" : "HTTP_ERROR";
        };
        String detail = switch (status) {
            case 400 -> "Invalid request.";
            case 403 -> "Development access is unavailable.";
            case 404 -> "Project is unavailable.";
            case 409 -> "Project has changed. Read it again before modifying.";
            case 503 -> "Service is temporarily unavailable.";
            default -> status >= 500 ? "An internal error occurred." : "Request cannot be processed.";
        };
        var problem = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status), detail);
        problem.setProperty("code", code);
        return problem;
    }
}
