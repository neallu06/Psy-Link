package com.fallhack.psy_link.repo;

import com.fallhack.psy_link.model.Thought;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface ThoughtRepository extends JpaRepository<Thought, Long> {
    List<Thought> findBySpaceId(Long spaceId);
    long countBySpaceId(Long spaceId);

    /** Newest first (ids only ever grow), used for the Recents list. */
    List<Thought> findBySpaceIdInOrderByIdDesc(Collection<Long> spaceIds, Pageable pageable);
}