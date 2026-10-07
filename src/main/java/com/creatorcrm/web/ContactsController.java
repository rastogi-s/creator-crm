package com.creatorcrm.web;

import com.creatorcrm.contacts.ContactCsv;
import com.creatorcrm.contacts.ContactFiles;
import com.creatorcrm.contacts.ContactService;
import com.creatorcrm.contacts.GmailContacts;
import com.creatorcrm.domain.Brand;
import com.creatorcrm.domain.BrandContact;
import com.creatorcrm.domain.BrandContact.Role;
import com.creatorcrm.domain.ContactSource;
import com.creatorcrm.domain.Suppression;
import com.creatorcrm.repo.BrandRepo;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Contacts page API: everyone at every brand, ranked, with import, export, merge and do-not-email. */
@RestController
@RequestMapping("/api/contacts")
public class ContactsController {

    public record ContactInput(@Size(max = 320) String email, @Size(max = 200) String name, @Size(max = 200) String title,
                               Role role, @Size(max = 50) String phone, @Size(max = 2000) String notes) {}

    /**
     * An import: either CSV text (columns guessed from their names), or a grid from {@code /import/read} with the
     * columns she picked.
     */
    public record CsvInput(@Size(max = 5_000_000) String csv, ContactCsv.Origin origin, @Size(max = 200) List<String> headers,
                           @Size(max = ContactCsv.MAX_ROWS) List<List<String>> rows, Integer firstLine,
                           Map<String, Integer> mapping, @Size(max = 255) String fileName) {}

    /** A file to read, as base64. {@code claude}: read a picture with Claude, after she saw the price. */
    public record FileInput(@NotBlank @Size(max = 255) String name, @NotBlank @Size(max = 11_000_000) String data, boolean claude) {}

    public record MergeInput(Long keepBrandId, Long dropBrandId) {}

    public record Page(List<ContactService.View> contacts, List<ContactService.Duplicate> duplicates) {}

    private final ContactService contacts;
    private final GmailContacts gmail;
    private final ContactCsv csv;
    private final ContactFiles files;
    private final BrandRepo brands;

    public ContactsController(ContactService contacts, GmailContacts gmail, ContactCsv csv, ContactFiles files, BrandRepo brands) {
        this.contacts = contacts;
        this.gmail = gmail;
        this.csv = csv;
        this.files = files;
        this.brands = brands;
    }

    @GetMapping
    public Page list() {
        return new Page(contacts.all(), contacts.duplicates());
    }

    @GetMapping("/brand/{brandId}")
    public List<ContactService.View> forBrand(@PathVariable Long brandId) {
        return contacts.forBrand(brandId);
    }

    @PostMapping("/brand/{brandId}")
    public BrandContact add(@PathVariable Long brandId, @Valid @RequestBody ContactInput in) {
        Brand b = brands.findById(brandId).orElseThrow(() -> new IllegalArgumentException("That brand no longer exists"));
        return contacts.add(b.id, new ContactService.Found(in.email(), in.name(), in.title(), in.role(), in.phone(), null, null,
                        null, ContactSource.Kind.MANUAL, null))
                .orElseThrow(() -> new IllegalArgumentException("That email address can't be used. Check it, or it may be on your do-not-email list."));
    }

    @PutMapping("/{id}")
    public BrandContact update(@PathVariable Long id, @Valid @RequestBody ContactInput in) {
        return contacts.update(id, in.name(), in.title(), in.role(), in.phone(), in.notes());
    }

    /** Never email this person again (they asked, or she decided). */
    @PostMapping("/{id}/do-not-email")
    public BrandContact doNotEmail(@PathVariable Long id) {
        return contacts.doNotEmail(id, Suppression.Reason.MANUAL);
    }

    /** Delete everything about this person; only their address is kept, so they're never added or emailed again. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> forget(@PathVariable Long id) {
        contacts.forget(id);
        return ResponseEntity.noContent().build();
    }

    /** Re-read her email threads for people, replies and signatures, and re-rank everyone. */
    @PostMapping("/refresh")
    public GmailContacts.Result refresh() {
        return gmail.refresh();
    }

    @PostMapping("/merge")
    public Brand merge(@RequestBody MergeInput in) {
        return contacts.merge(in.keepBrandId(), in.dropBrandId());
    }

    /** Reads any file (spreadsheet, contact cards, PDF, picture) into a grid for the preview. Nothing is saved. */
    @PostMapping("/import/read")
    public ContactFiles.Read read(@Valid @RequestBody FileInput in) {
        byte[] data;
        try {
            data = Base64.getDecoder().decode(in.data().replaceFirst("^data:[^,]*,", ""));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Couldn't read that file. Try again.");
        }
        return files.read(in.name(), data, in.claude());
    }

    @PostMapping("/import/preview")
    public ContactCsv.Preview preview(@Valid @RequestBody CsvInput in) {
        if (in.rows() == null) return csv.preview(in.csv());
        return csv.preview(table(in), in.mapping());
    }

    @PostMapping("/import")
    public ContactCsv.Preview importCsv(@Valid @RequestBody CsvInput in) {
        if (in.origin() == null) throw new IllegalArgumentException("Say where this list came from");
        if (in.rows() == null) return csv.importCsv(in.csv(), in.origin());
        return csv.importTable(table(in), in.mapping(), in.origin(), in.fileName());
    }

    private static ContactCsv.Table table(CsvInput in) {
        if (in.headers() == null || in.headers().isEmpty()) throw new IllegalArgumentException("That file has no columns");
        return new ContactCsv.Table(in.headers(), in.rows(), in.firstLine() == null ? 1 : in.firstLine());
    }

    @GetMapping("/export.csv")
    public ResponseEntity<String> export() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"brand-contacts.csv\"")
                .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .body("﻿" + csv.export(contacts.all()));
    }
}
