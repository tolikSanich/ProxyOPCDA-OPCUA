package com.opcproxy.persistence.repository;

import com.opcproxy.persistence.entity.Tag;
import com.opcproxy.persistence.enums.SourceType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface TagRepository extends JpaRepository<Tag, Long> {

    @Query("select t from Tag t")
    @EntityGraph(attributePaths = {"connection", "intervalProfile"})
    List<Tag> findAll();

    Optional<Tag> findByName(String name);
    boolean existsByName(String name);

    @EntityGraph(attributePaths = {"connection", "intervalProfile"})
    List<Tag> findByConnectionId(Long connectionId);

    @EntityGraph(attributePaths = {"connection", "intervalProfile"})
    List<Tag> findBySourceType(SourceType sourceType);

    @EntityGraph(attributePaths = {"connection", "intervalProfile"})
    @Query("SELECT t FROM Tag t WHERE t.enabled = true")
    List<Tag> findAllEnabled();

    /**
     * Загружает включенные теги вместе с подключением (LEFT JOIN FETCH),
     * чтобы обращение к tag.getConnection().getName() не вызывало
     * LazyInitializationException вне транзакции.
     */
    @EntityGraph(attributePaths = {"connection", "intervalProfile"})
    @Query("SELECT DISTINCT t FROM Tag t LEFT JOIN FETCH t.connection WHERE t.enabled = true")
    List<Tag> findAllEnabledWithConnection();
}