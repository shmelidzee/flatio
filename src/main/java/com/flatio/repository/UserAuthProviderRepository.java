package com.flatio.repository;

import com.flatio.domain.user.UserAuthProvider;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserAuthProviderRepository extends JpaRepository<UserAuthProvider, Long> {
}
