package com.creatorcrm.domain;

import com.creatorcrm.domain.Enums.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

@Entity
@Table(name = "tasks")
public class Task {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public Long opportunityId;
    @Enumerated(EnumType.STRING) public TaskType type;
    public String description;
    @Enumerated(EnumType.STRING) public Priority priority;
    public LocalDate dueDate;
    @Enumerated(EnumType.STRING) public TaskStatus status;
    public Integer followupNumber;
    public Long sourceMessageId;
    /** Claude's 2-4 sentence brief of what the email asks and what the opportunity is. */
    public String brief;
    /** Links from the email she needs for the to-do, as JSON; read through {@link #getLinks()}. */
    @JsonIgnore public String linksJson;
    public OffsetDateTime createdAt;
    public OffsetDateTime completedAt;

    public record Link(String label, String url) {}

    private static final ObjectMapper JSON = new ObjectMapper();

    public List<Link> getLinks() {
        if (linksJson == null || linksJson.isBlank()) return List.of();
        try {
            return JSON.readValue(linksJson, new TypeReference<List<Link>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    public void setLinks(List<Link> links) {
        try {
            linksJson = links == null || links.isEmpty() ? null : JSON.writeValueAsString(links);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
