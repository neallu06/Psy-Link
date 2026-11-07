package com.fallhack.psy_link.repo;

import com.fallhack.psy_link.model.IdeaSpace;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface IdeaSpaceRepository extends JpaRepository<IdeaSpace, Long> {
    List<IdeaSpace> findByOwnerId(Long ownerId);
}
