package com.cms.core.account;

import com.cms.core.history.HistoryWriter;
import java.util.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

public class AdminService {
  private final AdminAccountRepository accounts;
  private final AdminRoleRepository roles;
  private final AdministrationStateRepository state;
  private final HistoryWriter history;
  private final PasswordEncoder passwords = AdminPasswords.encoder();
  private final String dummyHash = passwords.encode(UUID.randomUUID().toString());

  public AdminService(
      AdminAccountRepository accounts,
      AdminRoleRepository roles,
      AdministrationStateRepository state,
      HistoryWriter history) {
    this.accounts = accounts;
    this.roles = roles;
    this.state = state;
    this.history = history;
  }

  public static String normalize(String login) {
    return login == null ? "" : login.strip().toLowerCase(Locale.ROOT);
  }

  private static void required(String value) {
    if (value == null || value.isBlank()) throw new AdminProblem(400, "Invalid input");
  }

  @Transactional
  public AdminIdentity bootstrap(String login, String display, String password) {
    var singleton = state.lock();
    if (singleton.bootstrapCompleted || accounts.count() != 0)
      throw new AdminProblem(409, "Bootstrap unavailable");
    var result = create(null, login, display, password, true);
    singleton.bootstrapCompleted = true;
    return result;
  }

  @Transactional(readOnly = true)
  public AdminIdentity authenticate(String login, String password) {
    var account = accounts.findByLoginId(normalize(login)).orElse(null);
    boolean matches =
        passwords.matches(
            password == null ? "" : password, account == null ? dummyHash : account.passwordHash);
    if (!matches || account == null || !account.active) throw AdminProblem.unauthorized();
    return account.identity();
  }

  @Transactional(readOnly = true)
  public AdminIdentity current(AdminIdentity identity) {
    var account = accounts.findById(identity.id()).orElseThrow(AdminProblem::unauthorized);
    if (!account.active || account.authVersion != identity.authVersion())
      throw AdminProblem.unauthorized();
    return account.identity();
  }

  private AdminAccount actor(AdminIdentity identity, boolean systemAdmin) {
    var fresh = current(identity);
    if (systemAdmin && (!fresh.systemAdmin() || fresh.mustChangePassword()))
      throw new AdminProblem(403, "Forbidden");
    return accounts.findById(fresh.id()).orElseThrow(AdminProblem::unauthorized);
  }

  @Transactional
  public AdminIdentity createAccount(
      AdminIdentity actor, String login, String display, String password) {
    state.lock();
    actor(actor, true);
    return create(actor.id(), login, display, password, false);
  }

  private AdminIdentity create(
      String actor, String login, String display, String password, boolean system) {
    required(login);
    required(display);
    required(password);
    if (accounts.findByLoginId(normalize(login)).isPresent())
      throw new AdminProblem(409, "Account already exists");
    var account = new AdminAccount();
    account.loginId = normalize(login);
    account.displayName = display.strip();
    account.passwordHash = passwords.encode(password);
    if (system) account.roles.add(roles.findByCode("SYSTEM_ADMIN").orElseThrow());
    accounts.saveAndFlush(account);
    history.account(actor, "CREATE", account.getId(), null, snapshot(account));
    return account.identity();
  }

  @Transactional(readOnly = true)
  public List<AdminIdentity> list(AdminIdentity actor) {
    actor(actor, true);
    return accounts.findAll().stream().map(AdminAccount::identity).toList();
  }

  @Transactional
  public void changePassword(AdminIdentity identity, String oldPassword, String newPassword) {
    state.lock();
    var account = actor(identity, false);
    required(newPassword);
    if (oldPassword == null || !passwords.matches(oldPassword, account.passwordHash))
      throw AdminProblem.unauthorized();
    var before = snapshot(account);
    account.passwordHash = passwords.encode(newPassword);
    account.authVersion++;
    account.mustChangePassword = false;
    accounts.flush();
    history.account(identity.id(), "PASSWORD_CHANGE", account.getId(), before, snapshot(account));
  }

  @Transactional
  public AdminIdentity setActive(AdminIdentity identity, String id, boolean active) {
    state.lock();
    actor(identity, true);
    var account = accounts.findById(id).orElseThrow(() -> new AdminProblem(404, "Not found"));
    if (!active && account.active && account.isSystemAdmin() && accounts.activeSystemAdmins() <= 1)
      throw new AdminProblem(409, "Last active system administrator");
    var before = snapshot(account);
    if (account.active != active) {
      account.active = active;
      account.authVersion++;
    }
    accounts.flush();
    history.account(identity.id(), "STATUS_CHANGE", id, before, snapshot(account));
    return account.identity();
  }

  private Map<String, Object> snapshot(AdminAccount account) {
    return Map.of(
        "loginId",
        account.loginId,
        "displayName",
        account.displayName,
        "active",
        account.active,
        "mustChangePassword",
        account.mustChangePassword,
        "roles",
        account.roles.stream().map(AdminRole::getCode).sorted().toList());
  }
}
