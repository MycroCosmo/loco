package com.cms.core.account;

public class AdminProblem extends RuntimeException {
  private final int status;
  private final String code;

  public AdminProblem(int status, String message) {
    this(status, message, defaultCode(status));
  }

  public AdminProblem(int status, String message, String code) {
    super(message);
    this.status = status;
    this.code = code;
  }

  public String code() {
    return code;
  }

  public static String defaultCode(int status) {
    return switch (status) {
      case 400 -> "INVALID_INPUT";
      case 401 -> "UNAUTHORIZED";
      case 403 -> "PERMISSION_DENIED";
      case 404 -> "NOT_FOUND";
      case 409 -> "CONFLICT";
      case 413 -> "FILE_TOO_LARGE";
      case 415 -> "UNSUPPORTED_MEDIA_TYPE";
      case 429 -> "RATE_LIMITED";
      default -> "INTERNAL_ERROR";
    };
  }

  public static AdminProblem forbidden() {
    return new AdminProblem(403, "Forbidden", "PERMISSION_DENIED");
  }

  public static AdminProblem passwordRequired() {
    return new AdminProblem(403, "Password change required", "PASSWORD_CHANGE_REQUIRED");
  }

  public int status() {
    return status;
  }

  public static AdminProblem unauthorized() {
    return new AdminProblem(401, "Unauthorized");
  }
}
