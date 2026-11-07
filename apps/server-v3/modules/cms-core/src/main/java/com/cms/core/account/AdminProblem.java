package com.cms.core.account;

public class AdminProblem extends RuntimeException {
  private final int status;

  public AdminProblem(int status, String message) {
    super(message);
    this.status = status;
  }

  public int status() {
    return status;
  }

  public static AdminProblem unauthorized() {
    return new AdminProblem(401, "Unauthorized");
  }
}
