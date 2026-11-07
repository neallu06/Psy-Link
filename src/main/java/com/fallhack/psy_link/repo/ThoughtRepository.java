package com.fallhack.psy_link.repo;

import com.fallhack.psy_link.model.Thought;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ThoughtRepository extends JpaRepository<Thought, Long> {
    List<Thought> findBySpaceId(Long spaceId);
    long countBySpaceId(Long spaceId);
}