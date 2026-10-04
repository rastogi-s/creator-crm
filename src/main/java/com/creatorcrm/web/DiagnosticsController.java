package com.creatorcrm.web;

import com.creatorcrm.diagnostics.ErrorReporter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Error reports: status for Settings, "Report a problem", and the redacted log file. */
@RestController
@RequestMapping("/api/diagnostics")
public class DiagnosticsController {

    public record AutoReport(boolean enabled) {}

    public record Problem(@Size(max = 4000) String note) {}

    private final ErrorReporter reporter;

    public DiagnosticsController(ErrorReporter reporter) {
        this.reporter = reporter;
    }

    @GetMapping
    public ErrorReporter.Status status() {
        return reporter.status();
    }

    @PutMapping("/auto-report")
    public ErrorReporter.Status autoReport(@RequestBody AutoReport body) {
        reporter.setAutoReport(body.enabled());
        return reporter.status();
    }

    @PostMapping("/report")
    public ErrorReporter.Sent report(@Valid @RequestBody Problem body) {
        return reporter.reportProblem(body.note());
    }

    @GetMapping("/log")
    public ResponseEntity<String> log() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"creator-crm-log-" + LocalDate.now() + ".txt\"")
                .contentType(new MediaType("text", "plain", java.nio.charset.StandardCharsets.UTF_8))
                .body(reporter.redactedLog());
    }
}
