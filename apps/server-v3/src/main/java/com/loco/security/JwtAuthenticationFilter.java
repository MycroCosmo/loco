package com.loco.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.filter.OncePerRequestFilter;

public class JwtAuthenticationFilter extends OncePerRequestFilter {
  // passport-jwt와 같이 인증 scheme을 대소문자 구분 없이 읽는다.
  private static final Pattern BEARER = Pattern.compile("(\\S+)\\s+(\\S+)");
  private final JwtDecoder decoder;

  public JwtAuthenticationFilter(JwtDecoder decoder) {
    this.decoder = decoder;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String header = request.getHeader("Authorization");
    if (header != null) {
      var match = BEARER.matcher(header);
      if (match.find() && match.group(1).equalsIgnoreCase("Bearer")) {
        try {
          var jwt = decoder.decode(match.group(2));
          var principal = new JwtPrincipal(jwt.getSubject(), jwt.getClaimAsString("email"));
          var context = SecurityContextHolder.createEmptyContext();
          context.setAuthentication(
              UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
          SecurityContextHolder.setContext(context);
        } catch (JwtException exception) {
          // 공개 경로와 없는 경로는 잘못된 토큰 때문에 401로 바뀌지 않는다.
          SecurityContextHolder.clearContext();
        }
      }
    }
    chain.doFilter(request, response);
  }
}
