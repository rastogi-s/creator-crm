package com.creatorcrm.web;

import com.creatorcrm.digest.DigestService;
import com.creatorcrm.domain.Activity;
import com.creatorcrm.domain.Brand;
import com.creatorcrm.domain.Deadline;
import com.creatorcrm.domain.Draft;
import com.creatorcrm.domain.Enums.DraftStatus;
import com.creatorcrm.domain.Enums.DraftType;
import com.creatorcrm.domain.Enums.OpportunityStatus;
import com.creatorcrm.domain.Enums.Priority;
import com.creatorcrm.domain.Enums.TaskStatus;
import com.creatorcrm.domain.Enums.TaskType;
import com.creatorcrm.domain.FollowUp;
import com.creatorcrm.domain.Message;
import com.creatorcrm.domain.Opportunity;
import com.creatorcrm.domain.Task;
import com.creatorcrm.drafts.DraftService;
import com.creatorcrm.ingest.IngestionService;
import com.creatorcrm.jobs.ScheduledJobs;
import com.creatorcrm.llm.Intent;
import com.creatorcrm.repo.ActivityRepo;
import com.creatorcrm.repo.BrandRepo;
import com.creatorcrm.repo.DeadlineRepo;
import com.creatorcrm.repo.DraftRepo;
import com.creatorcrm.repo.FollowUpRepo;
import com.creatorcrm.repo.MessageRepo;
import com.creatorcrm.repo.OpportunityRepo;
import com.creatorcrm.repo.TaskRepo;
import com.creatorcrm.settings.SettingsService;
import com.creatorcrm.workflow.FollowUpEngine;
import com.creatorcrm.workflow.OutreachService;
import com.creatorcrm.workflow.WorkflowEngine;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Dashboard API. Requires the admin session (see SecurityConfig). */
@RestController
@RequestMapping("/api")
public class CrmController {

    public record OpportunityView(Long id, String brand, String status, String statusLabel, String type,
                                  String compensation, String budget, String deliverables, String nextStep,
                                  String origin, LocalDate nextFollowUp, Integer nextFollowUpNumber,
                                  long openTasks, OffsetDateTime updatedAt) {}

    public record OpportunityDetail(OpportunityView summary, Opportunity opportunity, Brand brand, List<Task> tasks,
                                    List<FollowUp> followUps, List<Deadline> deadlines, List<MessageView> messages,
                                    List<Draft> drafts, List<Activity> activity) {}

    /** Message content is third-party text: the UI renders it as plain text only. */
    public record MessageView(Long id, String direction, String from, String subject, String content,
                              OffsetDateTime sentAt, String type) {}

    public record DraftView(Draft draft, String brand, String blockedReason) {}

    public record UpdateOpportunity(String status, @Size(max = 500) String budgetText,
                                    @Size(max = 2000) String deliverables, @Size(max = 1000) String usageRights,
                                    @Size(max = 1000) String nextStep, @Size(max = 200) String closedReason) {}

    public record NewTask(Long opportunityId, @NotBlank @Size(max = 500) String description, String priority,
                          LocalDate dueDate, String type) {}

    public record NewDraft(@NotBlank String type, @Size(max = 1000) String instructions) {}

    public record DraftEdit(@Size(max = 1000) String subject, @Size(max = 20000) String body) {}

    private final DigestService digest;
    private final OpportunityRepo opportunities;
    private final BrandRepo brands;
    private final TaskRepo tasks;
    private final FollowUpRepo followUpRepo;
    private final DeadlineRepo deadlines;
    private final MessageRepo messages;
    private final DraftRepo drafts;
    private final ActivityRepo activity;
    private final WorkflowEngine workflow;
    private final FollowUpEngine followUps;
    private final DraftService draftService;
    private final OutreachService outreach;
    private final IngestionService ingestion;
    private final ScheduledJobs jobs;
    private final SettingsService settings;
    private final TaskExecutor executor;

    public CrmController(DigestService digest, OpportunityRepo opportunities, BrandRepo brands, TaskRepo tasks,
                         FollowUpRepo followUpRepo, DeadlineRepo deadlines, MessageRepo messages, DraftRepo drafts,
                         ActivityRepo activity, WorkflowEngine workflow, FollowUpEngine followUps,
                         DraftService draftService, OutreachService outreach, IngestionService ingestion,
                         ScheduledJobs jobs, SettingsService settings,
                         @Qualifier("applicationTaskExecutor") TaskExecutor executor) {
        this.digest = digest;
        this.opportunities = opportunities;
        this.brands = brands;
        this.tasks = tasks;
        this.followUpRepo = followUpRepo;
        this.deadlines = deadlines;
        this.messages = messages;
        this.drafts = drafts;
        this.activity = activity;
        this.workflow = workflow;
        this.followUps = followUps;
        this.draftService = draftService;
        this.outreach = outreach;
        this.ingestion = ingestion;
        this.jobs = jobs;
        this.settings = settings;
        this.executor = executor;
    }

    // ---------- daily views ----------

    @GetMapping("/today")
    public DigestService.Morning today() {
        return digest.morning();
    }

    @GetMapping("/summary/eod")
    public DigestService.EndOfDay endOfDay() {
        return digest.endOfDay();
    }

    @GetMapping("/statuses")
    public Map<String, String> statuses() {
        Map<String, String> m = new LinkedHashMap<>();
        for (OpportunityStatus s : OpportunityStatus.values()) m.put(s.name(), s.label);
        return m;
    }

    // ---------- pipeline ----------

    @GetMapping("/pipeline")
    public List<OpportunityView> pipeline(@RequestParam(defaultValue = "false") boolean includeClosed) {
        return opportunities.findAll().stream()
                .filter(o -> includeClosed || o.status.isOpen())
                .sorted((a, b) -> b.updatedAt.compareTo(a.updatedAt))
                .map(this::view).toList();
    }

    @GetMapping("/opportunities/{id}")
    public OpportunityDetail opportunity(@PathVariable Long id) {
        Opportunity o = opportunities.findById(id).orElseThrow();
        List<MessageView> msgs = o.conversationId == null ? List.of()
                : messages.findByConversationIdOrderBySentAtAsc(o.conversationId).stream().map(CrmController::messageView).toList();
        return new OpportunityDetail(view(o), o, brands.findById(o.brandId).orElse(null),
                tasks.findByOpportunityIdOrderByCreatedAtDesc(id), followUpRepo.findByOpportunityIdOrderByNumberAsc(id),
                deadlines.findByOpportunityIdOrderByDueDateAsc(id), msgs,
                drafts.findByOpportunityIdAndStatus(id, DraftStatus.PENDING), activity.findByOpportunityIdOrderByAtDesc(id));
    }

    @PatchMapping("/opportunities/{id}")
    @Transactional
    public OpportunityView update(@PathVariable Long id, @Valid @RequestBody UpdateOpportunity u) {
        Opportunity o = opportunities.findById(id).orElseThrow();
        if (u.budgetText() != null) o.budgetText = u.budgetText();
        if (u.deliverables() != null) o.deliverables = u.deliverables();
        if (u.usageRights() != null) o.usageRights = u.usageRights();
        if (u.nextStep() != null) o.nextStep = u.nextStep();
        if (u.closedReason() != null) o.closedReason = u.closedReason();
        if (u.status() != null) workflow.setStatus(o, OpportunityStatus.valueOf(u.status()));
        o.updatedAt = OffsetDateTime.now();
        return view(opportunities.save(o));
    }

    @PostMapping("/opportunities/{id}/followups/sent")
    public OpportunityView followUpSent(@PathVariable Long id) {
        Opportunity o = opportunities.findById(id).orElseThrow();
        workflow.onCreatorMessage(o, Intent.CREATOR_FOLLOW_UP, settings.today());
        return view(o);
    }

    @PostMapping("/opportunities/{id}/followups/stop")
    public OpportunityView stopFollowUps(@PathVariable Long id) {
        Opportunity o = opportunities.findById(id).orElseThrow();
        followUps.stop(o);
        return view(o);
    }

    @PostMapping("/opportunities/{id}/drafts")
    public Draft newDraft(@PathVariable Long id, @Valid @RequestBody NewDraft d) {
        return draftService.generate(id, DraftType.valueOf(d.type()), d.instructions(), null, null);
    }

    // ---------- tasks & deadlines ----------

    @PostMapping("/tasks")
    public Task createTask(@Valid @RequestBody NewTask n) {
        if (n.opportunityId() != null) opportunities.findById(n.opportunityId()).orElseThrow();
        Task t = new Task();
        t.opportunityId = n.opportunityId();
        t.description = n.description().strip();
        t.priority = n.priority() == null ? Priority.MEDIUM : Priority.valueOf(n.priority());
        t.type = n.type() == null ? TaskType.OTHER : TaskType.valueOf(n.type());
        t.dueDate = n.dueDate();
        t.status = TaskStatus.OPEN;
        t.createdAt = OffsetDateTime.now();
        return tasks.save(t);
    }

    @PostMapping("/tasks/{id}/done")
    public Task completeTask(@PathVariable Long id) {
        Task t = tasks.findById(id).orElseThrow();
        workflow.completeTask(t);
        return t;
    }

    @PostMapping("/tasks/{id}/dismiss")
    public Task dismissTask(@PathVariable Long id) {
        Task t = tasks.findById(id).orElseThrow();
        t.status = TaskStatus.DISMISSED;
        t.completedAt = OffsetDateTime.now();
        return tasks.save(t);
    }

    @PostMapping("/deadlines/{id}/done")
    public Deadline deadlineDone(@PathVariable Long id) {
        Deadline d = deadlines.findById(id).orElseThrow();
        d.done = true;
        return deadlines.save(d);
    }

    // ---------- drafts (approval queue) ----------

    @GetMapping("/drafts")
    public List<DraftView> pendingDrafts() {
        List<DraftView> out = new ArrayList<>();
        for (Draft d : drafts.findByStatusOrderByCreatedAtAsc(DraftStatus.PENDING)) {
            String brand = opportunities.findById(d.opportunityId).map(workflow::brandName).orElse("");
            out.add(new DraftView(d, brand, draftService.sendBlockedReason(d).orElse(null)));
        }
        return out;
    }

    @PutMapping("/drafts/{id}")
    public Draft editDraft(@PathVariable Long id, @Valid @RequestBody DraftEdit e) {
        return draftService.edit(id, e.subject(), e.body());
    }

    /** The only way a message leaves the app: an explicit, CSRF-protected click by the signed-in creator. */
    @PostMapping("/drafts/{id}/send")
    public Draft sendDraft(@PathVariable Long id, @Valid @RequestBody(required = false) DraftEdit e) {
        return draftService.send(id, e == null ? null : e.subject(), e == null ? null : e.body());
    }

    @PostMapping("/drafts/{id}/sent-manually")
    public Draft sentManually(@PathVariable Long id) {
        return draftService.markSentManually(id);
    }

    @PostMapping("/drafts/{id}/discard")
    public Draft discard(@PathVariable Long id) {
        return draftService.discard(id);
    }

    // ---------- outreach ----------

    @GetMapping("/pitches")
    public List<OutreachService.PitchRow> pitches() {
        return outreach.pitches();
    }

    @PostMapping("/pitches")
    public OpportunityView logPitch(@RequestBody OutreachService.PitchRequest r) {
        return view(outreach.logPitch(r));
    }

    // ---------- sync ----------

    @PostMapping("/sync")
    public ResponseEntity<Map<String, String>> sync() {
        executor.execute(() -> {
            ingestion.syncAll();
            jobs.prepareMorning();
        });
        return ResponseEntity.accepted().body(Map.of("status", "started"));
    }

    @GetMapping("/sync/status")
    public IngestionService.Status syncStatus() {
        return ingestion.status();
    }

    @GetMapping("/enums")
    public Map<String, List<String>> enums() {
        return Map.of(
                "draftTypes", Arrays.stream(DraftType.values()).map(Enum::name).toList(),
                "taskTypes", Arrays.stream(TaskType.values()).map(Enum::name).toList(),
                "priorities", Arrays.stream(Priority.values()).map(Enum::name).toList());
    }

    private OpportunityView view(Opportunity o) {
        FollowUp next = followUps.scheduled(o.id).orElse(null);
        return new OpportunityView(o.id, workflow.brandName(o), o.status.name(), o.status.label, o.type.name(),
                o.compensation.name(), o.budgetText, o.deliverables, o.nextStep, o.origin.name(),
                next == null ? null : next.scheduledDate, next == null ? null : next.number,
                tasks.findByOpportunityIdAndStatus(o.id, TaskStatus.OPEN).size(), o.updatedAt);
    }

    private static MessageView messageView(Message m) {
        return new MessageView(m.id, m.direction.name(),
                m.senderName == null || m.senderName.isBlank() ? m.sender : m.senderName,
                m.subject, m.content, m.sentAt, m.messageType);
    }
}
