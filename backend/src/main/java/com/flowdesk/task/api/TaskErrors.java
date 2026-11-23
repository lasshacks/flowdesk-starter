package com.flowdesk.task.api;

import com.flowdesk.identity.application.ActorUnavailableException;
import com.flowdesk.task.application.TaskNotFoundException;
import com.flowdesk.task.domain.InvalidTaskInputException;
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

@RestControllerAdvice(assignableTypes = {TaskController.class, TaskCreateController.class, TaskUpdateController.class, TaskStatusController.class, TaskStatusWriteDisabledController.class, TaskStatusHistoryController.class, TaskArchiveController.class})
@Profile("dev-actor")
public class TaskErrors extends ResponseEntityExceptionHandler {
    @ExceptionHandler({com.flowdesk.task.application.TaskVersionConflictException.class,
            org.springframework.dao.OptimisticLockingFailureException.class,jakarta.persistence.OptimisticLockException.class})
    ResponseEntity<Object> conflict(Exception exception, WebRequest request) {
        return handleExceptionInternal(exception,null,new HttpHeaders(),HttpStatus.CONFLICT,request);
    }

    @ExceptionHandler(com.flowdesk.task.application.TaskForbiddenException.class)
    ResponseEntity<Object> forbidden(Exception exception, WebRequest request) {
        var body = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Task modification is unavailable.");
        body.setProperty("code", "TASK_FORBIDDEN");
        return new ResponseEntity<>(body, new HttpHeaders(), HttpStatus.FORBIDDEN);
    }

    @ExceptionHandler(InvalidTaskInputException.class)
    ResponseEntity<Object> invalidName(Exception exception, WebRequest request) {
        return handleExceptionInternal(exception, null, new HttpHeaders(), HttpStatus.BAD_REQUEST, request);
    }
    @ExceptionHandler(com.flowdesk.task.application.TaskProjectArchivedException.class)
    ResponseEntity<Object> projectArchived(Exception exception, WebRequest request) {
        var body=ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,"Archived projects do not accept Task changes.");
        body.setProperty("code","PROJECT_ARCHIVED");
        return new ResponseEntity<>(body,new HttpHeaders(),HttpStatus.CONFLICT);
    }
    @ExceptionHandler(com.flowdesk.task.application.TaskArchivedException.class)
    ResponseEntity<Object> taskArchived(Exception exception, WebRequest request) {
        var body=ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,"Archived tasks cannot be changed.");
        body.setProperty("code","TASK_ARCHIVED");
        return new ResponseEntity<>(body,new HttpHeaders(),HttpStatus.CONFLICT);
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

    @ExceptionHandler(TaskNotFoundException.class)
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
            logger.error("Task request failure type: " + cause.getClass().getName());
        }
        return handleExceptionInternal(exception, null, new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR, request);
    }

    static ProblemDetail problem(int status) {
        String code = switch (status) {
            case 400 -> "INVALID_REQUEST";
            case 409 -> "TASK_VERSION_CONFLICT";
            case 403 -> "DEV_ACCESS_DENIED";
            case 404 -> "TASK_NOT_FOUND";
            case 413 -> "REQUEST_TOO_LARGE";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            case 503 -> "SERVICE_UNAVAILABLE";
            default -> status >= 500 ? "INTERNAL_ERROR" : "HTTP_ERROR";
        };
        String detail = switch (status) {
            case 400 -> "Invalid request.";
            case 409 -> "Task has changed. Read it again before modifying.";
            case 403 -> "Development access is unavailable.";
            case 404 -> "Task is unavailable.";
            case 503 -> "Service is temporarily unavailable.";
            default -> status >= 500 ? "An internal error occurred." : "Request cannot be processed.";
        };
        var problem = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status), detail);
        problem.setProperty("code", code);
        return problem;
    }
}
