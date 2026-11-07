package com.fallhack.psy_space.repo;

import com.fallhack.psy_space.model.LinkType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LinkTypeRepository extends JpaRepository<LinkType, Long> {
    List<LinkType> findByOwnerId(Long ownerId);
}
