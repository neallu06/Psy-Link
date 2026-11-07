package com.fallhack.psy_space.model;

import jakarta.persistence.*;

@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String email;

    @Column(unique = true, nullable = false)
    private String username;

    /** BCrypt hash. The plain password is never stored. */
    @Column(nullable = false)
    private String passwordHash;

    /** "dark" or "light". */
    private String theme = "dark";

    public AppUser() {}

    public AppUser(String email, String username, String passwordHash) {
        this.email = email;
        this.username = username;
        this.passwordHash = passwordHash;
    }

    public Long getId() { return id; }
    public String getEmail() { return email; }
    public String getUsername() { return username; }
    public String getPasswordHash() { return passwordHash; }
    public String getTheme() { return theme; }
    public void setTheme(String theme) { this.theme = theme; }
}