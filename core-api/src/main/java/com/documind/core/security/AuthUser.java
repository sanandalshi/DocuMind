package com.documind.core.security;

import com.documind.core.user.Role;

import java.util.UUID;

/** The authenticated caller, taken from the JWT. */
public record AuthUser(UUID id, String email, Role role) {}
