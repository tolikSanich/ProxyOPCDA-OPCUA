package com.opcproxy.persistence.repository;

import com.opcproxy.persistence.entity.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {
    Optional<UserAccount> findByUsername(String username);

    @Query("select (count(u) > 0) from UserAccount u where u.username = ?1")
    boolean existsByUsername(String username);
}