package com.wf.fargo.automation.common;

import java.util.List;

public class ApiException extends RuntimeException {

    private final int status;
    private final String code;
    private final String detail;
    private final List<ApiError> errors;

    public ApiException(int status, String code, String detail) {
        this(status, code, detail, List.of());
    }

    public ApiException(int status, String code, String detail, List<ApiError> errors) {
        super(code);
        this.status = status;
        this.code = code;
        this.detail = detail;
        this.errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String detail() {
        return detail;
    }

    public List<ApiError> errors() {
        return errors;
    }
}
