package com.cms.core.web;

import com.cms.core.account.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.time.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class AdminSessionFilter extends OncePerRequestFilter {
  static final String START = "cms.session.started", LAST = "cms.session.last";
  private final AdminService admins;
  private final Clock clock;

  public AdminSessionFilter(AdminService admins, Clock clock) {
    this.admins = admins;
    this.clock = clock;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null) {
      var session = request.getSession(false);
      try {
        if (!(authentication.getPrincipal() instanceof AdminIdentity identity) || session == null)
          throw AdminProblem.unauthorized();
        synchronized (session) {
          var now = clock.instant();
          var started = (Instant) session.getAttribute(START);
          var last = (Instant) session.getAttribute(LAST);
          if (started == null
              || last == null
              || !now.isBefore(started.plus(Duration.ofHours(8)))
              || !now.isBefore(last.plus(Duration.ofMinutes(30))))
            throw AdminProblem.unauthorized();
          var fresh = admins.current(identity);
          var context = SecurityContextHolder.createEmptyContext();
          context.setAuthentication(
              UsernamePasswordAuthenticationToken.authenticated(fresh, null, java.util.List.of()));
          SecurityContextHolder.setContext(context);
          session.setAttribute(LAST, now);
          String path = request.getRequestURI().substring(request.getContextPath().length());
          if (fresh.mustChangePassword()
              && !java.util.Set.of(
                      "/admin/api/core/auth/me",
                      "/admin/api/core/auth/password",
                      "/admin/api/core/auth/logout",
                      "/admin/api/core/auth/csrf",
                      "/admin/api/core/auth/login")
                  .contains(path)) {
            response.setStatus(403);
            response.setContentType("application/json");
            response
                .getWriter()
                .write("{\"statusCode\":403,\"message\":\"Password change required\"}");
            return;
          }
        }
      } catch (AdminProblem | IllegalStateException exception) {
        if (session != null)
          try {
            session.invalidate();
          } catch (IllegalStateException ignored) {
          }
        SecurityContextHolder.clearContext();
      }
    }
    chain.doFilter(request, response);
  }
}
