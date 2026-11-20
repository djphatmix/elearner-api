package com.example.elearner.service;

import org.springframework.http.HttpStatus;

/** A failure whose message is safe to send to the browser. */
public class IngestException extends RuntimeException {

    private final HttpStatus status;

    public IngestException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
