package com.flatio.repository;

import com.flatio.domain.user.UserAuthProvider;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for {@link UserAuthProvider} entities.
 *
 * <p>Provides standard CRUD operations. Extended queries are added as use cases emerge.
 */
public interface UserAuthProviderRepository extends JpaRepository<UserAuthProvider, Long> {
}
