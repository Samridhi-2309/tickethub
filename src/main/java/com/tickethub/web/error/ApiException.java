package com.tickethub.web.error;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }

    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", message);
    }

    /** 409: the request was well-formed but the seat is no longer free. */
    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, "SEAT_UNAVAILABLE", message);
    }

    public static ApiException holdExpired(String message) {
        return new ApiException(HttpStatus.GONE, "HOLD_EXPIRED", message);
    }
}
