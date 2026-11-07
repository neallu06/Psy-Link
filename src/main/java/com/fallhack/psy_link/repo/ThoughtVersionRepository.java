package com.fallhack.psy_link.repo;

import com.fallhack.psy_link.model.ThoughtVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ThoughtVersionRepository extends JpaRepository<ThoughtVersion, Long> {
    List<ThoughtVersion> findByThoughtIdOrderByIdDesc(Long thoughtId);
    List<ThoughtVersion> findByThoughtIdOrderByIdAsc(Long thoughtId);
    long countByThoughtId(Long thoughtId);
}
