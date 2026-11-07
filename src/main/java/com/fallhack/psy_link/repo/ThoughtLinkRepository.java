package com.fallhack.psy_link.repo;

import com.fallhack.psy_link.model.ThoughtLink;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface ThoughtLinkRepository extends JpaRepository<ThoughtLink, Long> {
    List<ThoughtLink> findByFromIdInAndToIdIn(Collection<Long> from, Collection<Long> to);
    List<ThoughtLink> findByFromIdOrToId(Long fromId, Long toId);
}
