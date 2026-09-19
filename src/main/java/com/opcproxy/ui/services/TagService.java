package com.opcproxy.ui.services;

import com.opcproxy.calc.SpelCalcEngine;
import com.opcproxy.persistence.entity.Tag;
import com.opcproxy.persistence.repository.TagRepository;
import com.opcproxy.tags.TagRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class TagService {

    private final TagRepository tagRepository;
    private final TagRegistry tagRegistry;
    private final SpelCalcEngine calcEngine;   // новое поле (@RequiredArgsConstructor подхватит)

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
        calcEngine.invalidate(tag.getId());   // сброс кэша при любом сохранении тега
        return saved;
    }

    @Transactional
    public void delete(Long id) {
        calcEngine.invalidate(id);
        tagRegistry.unregisterTag(id);
        tagRepository.deleteById(id);
    }
    @Transactional
    public void setEnabled(Long id, boolean enabled) {
        tagRepository.findById(id).ifPresent(tag -> applyEnabled(tag, enabled));
    }
    /** П.1.2: все теги сервера следуют за сервером. @return число изменённых. */
    @Transactional
    public int setEnabledByConnection(Long connectionId, boolean enabled) {
        int changed = 0;
        for (Tag t : tagRepository.findByConnectionId(connectionId)) {
            if (applyEnabled(t, enabled)) changed++;
        }
        return changed;
    }

    /** П.1.4: bulk для набора id (отображаемые в detail теги). */
    @Transactional
    public int setEnabledMany(Collection<Long> ids, boolean enabled) {
        int changed = 0;
        for (Long id : ids) {
            Tag t = tagRepository.findById(id).orElse(null);
            if (t != null && applyEnabled(t, enabled)) changed++;
        }
        return changed;
    }
    /**
     * П.2: выключенный тег ОБЯЗАН быть Bad в реестре — тогда событие дойдёт
     * до AddressSpaceBuilder, и OPC UA-клиент увидит Bad вместо «застывшего»
     * последнего значения. Включённый — регистрируется заново.
     * @return true, если флаг изменился.
     */
    private boolean applyEnabled(Tag tag, boolean enabled) {
        boolean cur = Boolean.TRUE.equals(tag.getEnabled());
        if (cur == enabled) {
            if (!enabled) {   // идемпотентная страховка Bad-статуса
                tagRegistry.registerTag(tag);
                tagRegistry.updateTagValue(tag.getId(), null, "Bad_Disabled", java.time.Instant.now());
            }
            return false;
        }
        tag.setEnabled(enabled);
        tagRepository.save(tag);
        if (enabled) {
            tagRegistry.registerTag(tag);
        } else {
            tagRegistry.registerTag(tag);   // гарантируем наличие записи в реестре
            tagRegistry.updateTagValue(tag.getId(), null, "Bad_Disabled", java.time.Instant.now());
        }
        return true;
    }
}