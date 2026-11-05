package com.loco.users;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, String> {
  Optional<User> findByEmail(String email);

  Optional<User> findFirstByProviderAndProviderId(Provider provider, String providerId);

  interface AuthenticationState {
    String getId();

    String getEmail();

    java.time.LocalDateTime getDeletedAt();
  }

  Optional<AuthenticationState> findAuthenticationById(String id);
}
