package com.wf.fargo.automation.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemResponse> handle(ApiException exception) {
        return problem(exception.status(), exception.code(), exception.detail(), exception.errors());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemResponse> unreadable() {
        return problem(400, "validation_error", "Request body is required.", List.of());
    }

    private static ResponseEntity<ProblemResponse> problem(
            int status, String code, String detail, List<ApiError> errors) {
        ProblemResponse body = new ProblemResponse(
                "https://errors.fargo/automation/" + code,
                title(code),
                status,
                code,
                detail,
                errors);
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(body);
    }

    private static String title(String code) {
        return switch (code) {
            case "agent_limit_reached" -> "Agent limit reached";
            case "confirmation_invalid" -> "Confirmation invalid";
            case "policy_violation" -> "Policy violation";
            case "version_conflict" -> "Version conflict";
            case "agent_deleted" -> "Agent deleted";
            case "not_found" -> "Not found";
            case "raw_account_number" -> "Raw account number";
            case "validation_error" -> "Validation error";
            case "unauthorized" -> "Unauthorized";
            case "approval_not_pending" -> "Approval not pending";
            case "preview_changed" -> "Preview changed";
            case "step_up_required" -> "Step-up required";
            case "step_up_unavailable" -> "Step-up unavailable";
            default -> "Error";
        };
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record ProblemResponse(
            String type, String title, int status, String code, String detail,             List<ApiError> errors) {}
}
