package com.fallhack.psy_link.service;

import com.fallhack.psy_link.model.AppUser;
import com.fallhack.psy_link.model.IdeaSpace;
import com.fallhack.psy_link.model.Thought;
import com.fallhack.psy_link.repo.IdeaSpaceRepository;
import com.fallhack.psy_link.repo.ThoughtRepository;
import com.fallhack.psy_link.repo.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;

/** Every lookup is scoped to the logged-in user, so nobody can reach another user's data. */
@Service
public class AccessService {

    private final UserRepository users;
    private final IdeaSpaceRepository spaces;
    private final ThoughtRepository thoughts;

    public AccessService(UserRepository users, IdeaSpaceRepository spaces, ThoughtRepository thoughts) {
        this.users = users;
        this.spaces = spaces;
        this.thoughts = thoughts;
    }

    public AppUser me(Principal p) {
        return users.findByUsername(p.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }

    public IdeaSpace space(Long id, AppUser u) {
        return spaces.findById(id).filter(s -> u.getId().equals(s.getOwnerId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    public Thought thought(Long id, AppUser u) {
        Thought t = thoughts.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        space(t.getSpaceId(), u);
        return t;
    }
}
