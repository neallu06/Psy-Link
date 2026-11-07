package com.fallhack.psy_link.repo;

import com.fallhack.psy_link.model.SpaceSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SpaceSnapshotRepository extends JpaRepository<SpaceSnapshot, Long> {
    List<SpaceSnapshot> findBySpaceIdOrderByIdDesc(Long spaceId);
    long countBySpaceId(Long spaceId);
}
