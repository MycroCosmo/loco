package com.cms.core.web;

import com.cms.core.account.*;
import jakarta.servlet.http.*;
import java.time.Clock;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/api/core")
public class AdminController {
  private final AdminService admins;
  private final LoginAttempts attempts;
  private final Clock clock;
  private final HttpSessionSecurityContextRepository contexts;
  private final CookieCsrfTokenRepository csrf;

  public AdminController(
      AdminService admins,
      LoginAttempts attempts,
      Clock clock,
      HttpSessionSecurityContextRepository contexts,
      CookieCsrfTokenRepository csrf) {
    this.admins = admins;
    this.attempts = attempts;
    this.clock = clock;
    this.contexts = contexts;
    this.csrf = csrf;
  }

  // Deliberately no generated toString() for password-bearing request objects.
  public static class Login {
    public String loginId;
    public String password;
  }

  public static class NewAccount extends Login {
    public String displayName;
  }

  public static class Password {
    public String currentPassword;
    public String newPassword;
  }

  public record Status(Boolean active) {}

  @GetMapping("/auth/csrf")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void csrf(HttpServletRequest request) {
    request.getSession(true);
  }

  @PostMapping("/auth/login")
  public AdminIdentity login(
      @RequestBody Login input, HttpServletRequest request, HttpServletResponse response) {
    attempts.check(request.getRemoteAddr(), input.loginId);
    var identity = admins.authenticate(input.loginId, input.password);
    var authentication =
        UsernamePasswordAuthenticationToken.authenticated(identity, null, List.of());
    request.getSession(true);
    new ChangeSessionIdAuthenticationStrategy().onAuthentication(authentication, request, response);
    var session = request.getSession();
    session.setMaxInactiveInterval(1800);
    session.setAttribute(AdminSessionFilter.START, clock.instant());
    session.setAttribute(AdminSessionFilter.LAST, clock.instant());
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(authentication);
    SecurityContextHolder.setContext(context);
    contexts.saveContext(context, request, response);
    rotate(request, response);
    return identity;
  }

  @GetMapping("/auth/me")
  public AdminIdentity me(@AuthenticationPrincipal AdminIdentity identity) {
    return identity;
  }

  @PostMapping("/auth/logout")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void logout(HttpServletRequest request, HttpServletResponse response) {
    end(request, response);
  }

  @PutMapping("/auth/password")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void password(
      @AuthenticationPrincipal AdminIdentity identity,
      @RequestBody Password input,
      HttpServletRequest request,
      HttpServletResponse response) {
    admins.changePassword(identity, input.currentPassword, input.newPassword);
    end(request, response);
  }

  @GetMapping("/accounts")
  public List<AdminIdentity> accounts(@AuthenticationPrincipal AdminIdentity identity) {
    return admins.list(identity);
  }

  @PostMapping("/accounts")
  @ResponseStatus(HttpStatus.CREATED)
  public AdminIdentity create(
      @AuthenticationPrincipal AdminIdentity identity, @RequestBody NewAccount input) {
    return admins.createAccount(identity, input.loginId, input.displayName, input.password);
  }

  @PutMapping("/accounts/{id}/status")
  public AdminIdentity status(
      @AuthenticationPrincipal AdminIdentity identity,
      @PathVariable String id,
      @RequestBody Status input) {
    if (input.active() == null) throw new AdminProblem(400, "Invalid input");
    return admins.setActive(identity, id, input.active());
  }

  private void end(HttpServletRequest request, HttpServletResponse response) {
    var session = request.getSession(false);
    if (session != null) session.invalidate();
    SecurityContextHolder.clearContext();
    contexts.saveContext(SecurityContextHolder.createEmptyContext(), request, response);
    rotate(request, response);
  }

  private void rotate(HttpServletRequest request, HttpServletResponse response) {
    csrf.saveToken(null, request, response);
    csrf.saveToken(csrf.generateToken(request), request, response);
  }
}
