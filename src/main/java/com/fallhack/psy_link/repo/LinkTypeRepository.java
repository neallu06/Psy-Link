package com.fallhack.psy_link.repo;

import com.fallhack.psy_link.model.LinkType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LinkTypeRepository extends JpaRepository<LinkType, Long> {
    List<LinkType> findByOwnerId(Long ownerId);
}
