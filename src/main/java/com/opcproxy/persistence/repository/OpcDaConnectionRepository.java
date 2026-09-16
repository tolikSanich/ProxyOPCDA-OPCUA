package com.opcproxy.persistence.repository;

import com.opcproxy.persistence.entity.OpcDaConnection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface OpcDaConnectionRepository extends JpaRepository<OpcDaConnection, Long> {
    Optional<OpcDaConnection> findByName(String name);
    boolean existsByName(String name);
}