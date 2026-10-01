package com.creatorcrm.security;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SecretRepo extends JpaRepository<Secret, String> {}
