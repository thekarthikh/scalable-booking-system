package com.thekarthikh.user.controller;

import com.thekarthikh.user.dto.*;
import com.thekarthikh.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /** Register a new user account. */
    @PostMapping("/auth/register")
    public ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(userService.register(req));
    }

    /** Authenticate and obtain a JWT token. */
    @PostMapping("/auth/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest req) {
        return ResponseEntity.ok(userService.login(req));
    }

    /** Get current authenticated user's profile. */
    @GetMapping("/users/me")
    public ResponseEntity<UserResponse> me(@AuthenticationPrincipal UserDetails principal) {
        return ResponseEntity.ok(userService.getUserByUsername(principal.getUsername()));
    }

    /** Get user by ID (service-to-service or admin use). */
    @GetMapping("/users/{id}")
    public ResponseEntity<UserResponse> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(userService.getUserById(id));
    }

    /** Internal endpoint for token validation by other services. */
    @GetMapping("/auth/validate")
    public ResponseEntity<UserResponse> validate(@AuthenticationPrincipal UserDetails principal) {
        return ResponseEntity.ok(userService.getUserByUsername(principal.getUsername()));
    }
}
