package com.loco.web;

import java.util.List;
import java.util.Map;

public final class ApiProblem extends RuntimeException {
    private final int status;
    private final Object responseMessage;
    private final String error;

    private ApiProblem(int status, Object message, String error) {
        super(error);
        this.status = status;
        this.responseMessage = message;
        this.error = error;
    }
    public static ApiProblem unauthorized(String message) { return new ApiProblem(401, message, "Unauthorized"); }
    public static ApiProblem conflict(String message) { return new ApiProblem(409, message, "Conflict"); }
    public static ApiProblem notFound(String message) { return new ApiProblem(404, message, "Not Found"); }
    public static ApiProblem payloadTooLarge(String message) { return new ApiProblem(413, message, "Payload Too Large"); }
    public static ApiProblem badRequest(String message) { return new ApiProblem(400, message, "Bad Request"); }
    public static ApiProblem invalid(List<String> messages) { return new ApiProblem(400, List.copyOf(messages), "Bad Request"); }
    public int status() { return status; }
    public Map<String, Object> body() { return Map.of("statusCode", status, "message", responseMessage, "error", error); }
}
