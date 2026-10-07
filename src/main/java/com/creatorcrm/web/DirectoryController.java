package com.creatorcrm.web;

import com.creatorcrm.contacts.BrandDirectory;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The shareable brand directory: a preview of what's in it, and the CSV she can sell or share. */
@RestController
@RequestMapping("/api/contacts/directory")
public class DirectoryController {

    private final BrandDirectory directory;

    public DirectoryController(BrandDirectory directory) {
        this.directory = directory;
    }

    @GetMapping
    public BrandDirectory.Summary preview() {
        return directory.build();
    }

    @GetMapping("/export.csv")
    public ResponseEntity<String> export() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"brand-directory.csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body("﻿" + directory.csv());
    }
}
