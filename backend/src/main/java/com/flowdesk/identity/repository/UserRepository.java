package com.flowdesk.identity.repository;

import com.flowdesk.identity.domain.User;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Transactional(readOnly = true)
public interface UserRepository extends Repository<User, Long> {
    Optional<User> findById(Long id);
    Optional<User> findByEmail(String email);
}
