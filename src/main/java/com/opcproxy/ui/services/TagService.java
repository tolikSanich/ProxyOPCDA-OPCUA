package com.opcproxy.ui.services;

import com.opcproxy.persistence.entity.Tag;
import com.opcproxy.persistence.repository.TagRepository;
import com.opcproxy.tags.TagRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class TagService {

    private final TagRepository tagRepository;
    private final TagRegistry tagRegistry;

    public List<Tag> findAll() {
        return tagRepository.findAll();
    }

    public Optional<Tag> findById(Long id) {
        return tagRepository.findById(id);
    }

    public boolean existsByName(String name) {
        return tagRepository.existsByName(name);
    }

    @Transactional
    public Tag save(Tag tag) {
        Tag saved = tagRepository.save(tag);
        tagRegistry.registerTag(saved);   // тег появляется в реестре сразу
        return saved;
    }

    @Transactional
    public void delete(Long id) {
        tagRegistry.unregisterTag(id);
        tagRepository.deleteById(id);
    }
    @Transactional
    public void setEnabled(Long id, boolean enabled) {
        tagRepository.findById(id).ifPresent(tag -> {
            tag.setEnabled(enabled);
            tagRepository.save(tag);
            if (enabled) {
                tagRegistry.registerTag(tag);
            } else {
                tagRegistry.unregisterTag(id);
            }
        });
    }
}