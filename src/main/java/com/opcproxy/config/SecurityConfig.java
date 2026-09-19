package com.opcproxy.config;

import com.opcproxy.ui.views.LoginView;
import com.vaadin.flow.spring.security.VaadinSecurityConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.NoOpPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // API v1: те же пользователи, HTTP Basic (демо-стенд; прод — в бэклоге)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/**").authenticated()
                        .requestMatchers("/swagger-ui/**", "/swagger-ui.html",
                                "/v3/api-docs/**").permitAll()
                )
                // HTTP Basic для API (браузерный UI продолжает использовать form-login Vaadin)
                .httpBasic(Customizer.withDefaults())
                // Vaadin: form-login, CSRF для UI и пр.
                .with(VaadinSecurityConfigurer.vaadin(),
                        vaadinSecurity -> vaadinSecurity.loginView(LoginView.class));

        return http.build();
    }

    @Bean
    public UserDetailsService userDetailsService() {
        var user = User.builder()
                .username("admin")
                .password("admin")
                .roles("ADMIN", "USER")
                .build();
        return new InMemoryUserDetailsManager(user);
    }

    @Bean
    @SuppressWarnings("deprecation")
    public PasswordEncoder passwordEncoder() {
        // Для локальной разработки: NoOp, пароль "admin" принимается как есть.
        // БЭКЛОГ (прод): BCrypt + вынос пользователей в БД (ТЗ §7.3).
        return NoOpPasswordEncoder.getInstance();
    }
}