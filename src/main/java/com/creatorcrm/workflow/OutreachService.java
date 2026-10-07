package com.creatorcrm.workflow;

import com.creatorcrm.contacts.ContactService;
import com.creatorcrm.domain.Activity;
import com.creatorcrm.domain.Brand;
import com.creatorcrm.domain.ContactSource;
import com.creatorcrm.domain.Enums.Compensation;
import com.creatorcrm.domain.Enums.FollowUpStatus;
import com.creatorcrm.domain.Enums.OpportunityStatus;
import com.creatorcrm.domain.Enums.OpportunityType;
import com.creatorcrm.domain.Enums.Origin;
import com.creatorcrm.domain.FollowUp;
import com.creatorcrm.domain.Opportunity;
import com.creatorcrm.repo.ActivityRepo;
import com.creatorcrm.repo.BrandRepo;
import com.creatorcrm.repo.FollowUpRepo;
import com.creatorcrm.repo.OpportunityRepo;
import com.creatorcrm.settings.SettingsService;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The pitch database: brands the creator reached out to, with the follow-up #1-#N dates. */
@Service
public class OutreachService {

    public record PitchRequest(String brand, String contactName, String contactEmail, String instagram,
                               String platform, String opportunity, LocalDate pitchedAt, String notes, boolean force) {}

    /** contactSearch holds every contact detail, so the Outreach search finds an email even when a name is shown. */
    public record PitchRow(Long opportunityId, String brand, String contact, LocalDate pitchedAt, String platform,
                           String opportunity, String initialResponse, List<String> followUps, String status,
                           boolean closed, LocalDate nextFollowUp, String contactSearch) {}

    public record Duplicate(Long opportunityId, String brand, String status, LocalDate since) {}

    public static class DuplicatePitchException extends RuntimeException {
        public final Duplicate duplicate;

        DuplicatePitchException(Duplicate d) {
            super(d.brand() + " is already in your pipeline (" + d.status() + "). Pass force=true to log another pitch.");
            this.duplicate = d;
        }
    }

    private final BrandRepo brands;
    private final OpportunityRepo opportunities;
    private final FollowUpRepo followUps;
    private final ActivityRepo activity;
    private final FollowUpEngine engine;
    private final SettingsService settings;
    private final ContactService contacts;

    public OutreachService(BrandRepo brands, OpportunityRepo opportunities, FollowUpRepo followUps,
                           ActivityRepo activity, FollowUpEngine engine, SettingsService settings, ContactService contacts) {
        this.contacts = contacts;
        this.brands = brands;
        this.opportunities = opportunities;
        this.followUps = followUps;
        this.activity = activity;
        this.engine = engine;
        this.settings = settings;
    }

    /** Is this brand already in the pipeline? Prevents duplicate pitches. */
    public Optional<Duplicate> findExisting(String brandName) {
        return brands.findByNameKey(Brand.key(brandName))
                .flatMap(b -> opportunities.findByBrandIdOrderByIdDesc(b.id).stream().findFirst()
                        .map(o -> new Duplicate(o.id, b.name, o.status.label,
                                o.pitchedAt != null ? o.pitchedAt : o.createdAt.toLocalDate())));
    }

    @Transactional
    public Opportunity logPitch(PitchRequest r) {
        if (r.brand() == null || r.brand().isBlank()) throw new IllegalArgumentException("Brand is required");
        Optional<Duplicate> dup = findExisting(r.brand());
        if (dup.isPresent() && !r.force()) throw new DuplicatePitchException(dup.get());

        Brand b = brands.findByNameKey(Brand.key(r.brand())).orElseGet(() -> {
            Brand n = new Brand();
            n.name = r.brand().strip();
            n.nameKey = Brand.key(r.brand());
            n.createdAt = OffsetDateTime.now();
            return n;
        });
        if (blank(b.contactName)) b.contactName = r.contactName();
        if (blank(b.contactEmail)) b.contactEmail = r.contactEmail();
        if (blank(b.instagram)) b.instagram = r.instagram();
        b = brands.save(b);
        if (!blank(r.contactEmail())) {
            contacts.add(b.id, ContactService.Found.of(r.contactEmail(), r.contactName(), ContactSource.Kind.MANUAL, null));
        }

        LocalDate pitched = r.pitchedAt() == null ? settings.today() : r.pitchedAt();
        Opportunity o = new Opportunity();
        o.brandId = b.id;
        o.origin = Origin.PITCH;
        o.type = OpportunityType.OTHER;
        o.compensation = Compensation.UNKNOWN;
        o.status = OpportunityStatus.PITCHED;
        o.campaign = r.opportunity();
        o.nextStep = r.notes();
        o.pitchPlatform = r.platform();
        o.pitchedAt = pitched;
        o.createdAt = OffsetDateTime.now();
        o.updatedAt = o.createdAt;
        o = opportunities.save(o);
        activity.save(Activity.of(o.id, Activity.NEW_OPPORTUNITY, "Pitched " + b.name));
        engine.onCreatorMessage(o, pitched, false);
        return o;
    }

    public List<PitchRow> pitches() {
        List<PitchRow> rows = new ArrayList<>();
        for (Opportunity o : opportunities.findByOriginOrderByPitchedAtDesc(Origin.PITCH)) {
            Brand b = brands.findById(o.brandId).orElse(null);
            List<String> fu = new ArrayList<>();
            List<FollowUp> list = followUps.findByOpportunityIdOrderByNumberAsc(o.id);
            LocalDate next = list.stream().filter(f -> f.status == FollowUpStatus.SCHEDULED).map(f -> f.scheduledDate)
                    .filter(Objects::nonNull).min(LocalDate::compareTo).orElse(null);
            for (int n = 1; n <= settings.maxFollowups(); n++) {
                int num = n;
                fu.add(list.stream().filter(f -> f.number == num).reduce((a, x) -> x)
                        .map(f -> f.status == FollowUpStatus.DONE ? "✓ " + f.completedDate
                                : f.status == FollowUpStatus.SCHEDULED ? "due " + f.scheduledDate : "—")
                        .orElse(""));
            }
            rows.add(new PitchRow(o.id, b == null ? "?" : b.name,
                    b == null ? "" : firstNonBlank(b.contactName, b.contactEmail, b.instagram),
                    o.pitchedAt, o.pitchPlatform, firstNonBlank(o.campaign, o.type.name()),
                    o.initialResponse, fu, o.status.label, !o.status.isOpen(), next,
                    b == null ? "" : String.join(" ", Stream.of(b.contactName, b.contactEmail, b.instagram)
                            .filter(v -> !blank(v)).toList())));
        }
        return rows;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) if (!blank(v)) return v;
        return "";
    }
}
