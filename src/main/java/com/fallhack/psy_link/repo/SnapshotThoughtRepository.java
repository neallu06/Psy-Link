package com.fallhack.psy_link.repo;

import com.fallhack.psy_link.model.SnapshotThought;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SnapshotThoughtRepository extends JpaRepository<SnapshotThought, Long> {
    List<SnapshotThought> findBySnapshotId(Long snapshotId);
}
