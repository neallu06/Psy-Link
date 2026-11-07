package com.fallhack.psy_link.repo;

import com.fallhack.psy_link.model.SnapshotLink;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SnapshotLinkRepository extends JpaRepository<SnapshotLink, Long> {
    List<SnapshotLink> findBySnapshotId(Long snapshotId);
}
