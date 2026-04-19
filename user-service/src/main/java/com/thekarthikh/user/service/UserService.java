package com.thekarthikh.user.service;

import com.thekarthikh.user.dto.*;
import com.thekarthikh.user.entity.User;
import com.thekarthikh.user.exception.*;
import com.thekarthikh.user.repository.UserRepository;
import com.thekarthikh.user.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider tokenProvider;

    @Transactional
    public UserResponse register(RegisterRequest req) {
        if (userRepository.existsByUsername(req.getUsername())) {
            throw new UserAlreadyExistsException("Username already taken: " + req.getUsername());
        }
        if (userRepository.existsByEmail(req.getEmail())) {
            throw new UserAlreadyExistsException("Email already registered: " + req.getEmail());
        }

        User user = User.builder()
                .username(req.getUsername())
                .email(req.getEmail())
                .password(passwordEncoder.encode(req.getPassword()))
                .role("USER")
                .build();

        user = userRepository.save(user);
        log.info("Registered new user: {} ({})", user.getUsername(), user.getId());
        return toResponse(user);
    }

    public AuthResponse login(LoginRequest req) {
        Authentication auth = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(req.getUsername(), req.getPassword())
        );

        User user = userRepository.findByUsername(req.getUsername())
                .orElseThrow(() -> new UserNotFoundException("User not found: " + req.getUsername()));

        String token = tokenProvider.generateToken(
                user.getUsername(), user.getRole(), user.getId().toString());

        log.info("User logged in: {}", user.getUsername());
        return AuthResponse.builder()
                .token(token)
                .tokenType("Bearer")
                .expiresIn(tokenProvider.getExpirationMs())
                .user(toResponse(user))
                .build();
    }

    @Transactional(readOnly = true)
    public UserResponse getUserById(UUID id) {
        return userRepository.findById(id)
                .map(this::toResponse)
                .orElseThrow(() -> new UserNotFoundException("User not found: " + id));
    }

    @Transactional(readOnly = true)
    public UserResponse getUserByUsername(String username) {
        return userRepository.findByUsername(username)
                .map(this::toResponse)
                .orElseThrow(() -> new UserNotFoundException("User not found: " + username));
    }

    private UserResponse toResponse(User user) {
        return UserResponse.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .role(user.getRole())
                .createdAt(user.getCreatedAt())
                .build();
    }
}
