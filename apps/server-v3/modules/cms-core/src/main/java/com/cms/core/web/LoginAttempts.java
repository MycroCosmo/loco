package com.cms.core.web;

import com.cms.core.account.AdminProblem;
import com.cms.core.account.AdminService;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

/** Independent fixed one-minute windows; successful attempts also consume the approved quota. */
public class LoginAttempts {
  private final Clock clock;
  private final Map<String, Window> ips = new HashMap<>(), accounts = new HashMap<>();

  private record Window(Instant start, int count) {}

  public LoginAttempts(Clock clock) {
    this.clock = clock;
  }

  public synchronized void check(String ip, String login) {
    var now = clock.instant();
    ips.values().removeIf(w -> !now.isBefore(w.start.plusSeconds(60)));
    accounts.values().removeIf(w -> !now.isBefore(w.start.plusSeconds(60)));
    String account = AdminService.normalize(login);
    if (count(ips, ip) >= 5 || count(accounts, account) >= 5)
      throw new AdminProblem(429, "Too many requests");
    add(ips, ip, now);
    add(accounts, account, now);
  }

  private int count(Map<String, Window> map, String key) {
    return map.containsKey(key) ? map.get(key).count : 0;
  }

  private void add(Map<String, Window> map, String key, Instant now) {
    map.compute(key, (k, w) -> w == null ? new Window(now, 1) : new Window(w.start, w.count + 1));
  }
}
