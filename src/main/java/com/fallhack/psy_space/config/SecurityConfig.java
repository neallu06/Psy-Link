package com.fallhack.psy_space.config;

import com.fallhack.psy_space.repo.UserRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public UserDetailsService userDetailsService(UserRepository users) {
        return username -> users.findByUsername(username)
                .map(u -> User.withUsername(u.getUsername()).password(u.getPasswordHash()).roles("USER").build())
                .orElseThrow(() -> new UsernameNotFoundException("No such user"));
    }

    /**
     * Session-based login for a single-page app: the page and registration are public,
     * everything else needs a login, and failures return plain status codes (no redirects).
     * CSRF is disabled to keep v1 simple; turn it on before deploying beyond localhost.
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/", "/index.html", "/favicon.ico", "/css/**", "/js/**", "/api/auth/register").permitAll()
                .anyRequest().authenticated())
            .formLogin(form -> form
                .loginProcessingUrl("/api/auth/login")
                .successHandler((req, res, auth) -> res.setStatus(HttpStatus.OK.value()))
                .failureHandler((req, res, ex) -> res.setStatus(HttpStatus.UNAUTHORIZED.value()))
                .permitAll())
            .logout(logout -> logout
                .logoutUrl("/api/auth/logout")
                .logoutSuccessHandler((req, res, auth) -> res.setStatus(HttpStatus.OK.value())))
            .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
        return http.build();
    }
}
