package com.opcproxy.persistence.repository;

import com.opcproxy.persistence.entity.IntervalProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface IntervalProfileRepository extends JpaRepository<IntervalProfile, Long> {
    Optional<IntervalProfile> findByName(String name);
    boolean existsByName(String name);                                  // seed в сервисе
    boolean existsByUaSamplingIntervalMs(Integer uaSamplingIntervalMs); // (опц.) диагностика дублей
}