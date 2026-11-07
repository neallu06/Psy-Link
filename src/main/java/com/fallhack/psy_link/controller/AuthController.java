package com.fallhack.psy_link.controller;

import com.fallhack.psy_link.model.AppUser;
import com.fallhack.psy_link.model.IdeaSpace;
import com.fallhack.psy_link.model.LinkType;
import com.fallhack.psy_link.repo.IdeaSpaceRepository;
import com.fallhack.psy_link.repo.LinkTypeRepository;
import com.fallhack.psy_link.repo.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class AuthController {

    public record Register(String email, String username, String password) {}
    public record ThemeUpdate(String theme) {}

    private final UserRepository users;
    private final LinkTypeRepository types;
    private final IdeaSpaceRepository spaces;
    private final PasswordEncoder encoder;

    public AuthController(UserRepository users, LinkTypeRepository types,
                          IdeaSpaceRepository spaces, PasswordEncoder encoder) {
        this.users = users;
        this.types = types;
        this.spaces = spaces;
        this.encoder = encoder;
    }

    private static ResponseEntity<Map<String, String>> bad(int status, String message) {
        return ResponseEntity.status(status).body(Map.of("message", message));
    }

    @PostMapping("/auth/register")
    @Transactional
    public ResponseEntity<?> register(@RequestBody Register body) {
        String email = body.email() == null ? "" : body.email().trim().toLowerCase();
        String username = body.username() == null ? "" : body.username().trim();
        String password = body.password() == null ? "" : body.password();

        // Email format is intentionally not verified in v1 beyond "looks like an address".
        if (!email.contains("@")) return bad(400, "Please enter an email address.");
        if (username.length() < 3) return bad(400, "Username must be at least 3 characters.");
        if (password.length() < 8) return bad(400, "Password must be at least 8 characters.");
        if (users.existsByUsername(username)) return bad(409, "That username is taken.");
        if (users.existsByEmail(email)) return bad(409, "An account with that email already exists.");

        AppUser user = users.save(new AppUser(email, username, encoder.encode(password)));
        seedDefaults(user.getId());
        return ResponseEntity.ok(Map.of("username", user.getUsername()));
    }

    @GetMapping("/me")
    public Map<String, String> me(Principal principal) {
        AppUser u = users.findByUsername(principal.getName()).orElseThrow();
        return Map.of("username", u.getUsername(), "email", u.getEmail(), "theme", u.getTheme());
    }

    @PutMapping("/me/theme")
    public ResponseEntity<Void> setTheme(Principal principal, @RequestBody ThemeUpdate body) {
        if (!"dark".equals(body.theme()) && !"light".equals(body.theme())) {
            return ResponseEntity.badRequest().build();
        }
        AppUser u = users.findByUsername(principal.getName()).orElseThrow();
        u.setTheme(body.theme());
        users.save(u);
        return ResponseEntity.noContent().build();
    }

    /** Default connection types; the user can recolor them and switch solid/dotted afterwards. */
    private void seedDefaults(Long ownerId) {
        types.save(new LinkType(ownerId, "related", "Related to", "Two thoughts share a subject.", "#3ddc84", false));
        types.save(new LinkType(ownerId, "supports", "Supports", "One thought provides evidence for another.", "#4da3ff", false));
        types.save(new LinkType(ownerId, "contradicts", "Contradicts", "Two thoughts have opposing perspectives.", "#ff5252", false));
        types.save(new LinkType(ownerId, "develops", "Develops into", "One thought leads to another.", "#ffd740", false));
        types.save(new LinkType(ownerId, "part", "Part of", "A thought belongs to a larger idea.", "#b388ff", true));
        spaces.save(new IdeaSpace(ownerId, "My Thoughts", "Default space"));
    }
}