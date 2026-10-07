package com.creatorcrm.demo;

import com.creatorcrm.contacts.ContactService;
import com.creatorcrm.domain.Brand;
import com.creatorcrm.domain.BrandContact;
import com.creatorcrm.domain.ContactSource.Kind;
import com.creatorcrm.repo.BrandContactRepo;
import com.creatorcrm.repo.BrandRepo;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Demo mode: a few brand team inboxes (collabs@, pr@) so the Brand directory video has something to show, plus one
 * from a paid finder service, which the directory must leave out. Runs after {@link DemoData} made the brands.
 */
@Component
@Profile("demo")
public class DemoDirectory {

    private record Inbox(String brand, String website, String instagram, String email, Kind source, int replies) {}

    private static final List<Inbox> INBOXES = List.of(
            new Inbox("Glowberry Skin", "https://glowberry.example", "glowberryskin", "collabs@glowberry.example", Kind.WEBSITE, 2),
            new Inbox("Bloomleaf Tea", "https://bloomleaf.example", "bloomleaftea", "partnerships@bloomleaf.example", Kind.WEBSITE, 0),
            new Inbox("Bloomleaf Tea", null, null, "pr@bloomleaf.example", Kind.GMAIL, 1),
            new Inbox("Peak Trail Co", "https://peaktrail.example", "peaktrailco", "creators@peaktrail.example", Kind.WEBSITE, 0),
            new Inbox("Coastline Coffee", "https://coastlinecoffee.example", "coastlinecoffee", "hello@coastlinecoffee.example", Kind.WEBSITE, 0),
            new Inbox("Juniper Juice", null, null, "partnerships@juniperjuice.example", Kind.HUNTER, 0));

    private final ContactService contacts;
    private final BrandContactRepo contactRepo;
    private final BrandRepo brands;

    public DemoDirectory(ContactService contacts, BrandContactRepo contactRepo, BrandRepo brands) {
        this.contacts = contacts;
        this.contactRepo = contactRepo;
        this.brands = brands;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seed() {
        if (contactRepo.findByEmail(INBOXES.get(0).email()).isPresent()) return;
        OffsetDateTime now = OffsetDateTime.now();
        for (Inbox in : INBOXES) {
            Brand b = brands.findAll().stream().filter(x -> in.brand().equals(x.name)).findFirst().orElse(null);
            if (b == null) continue;
            if (in.website() != null && b.website == null) b.website = in.website();
            if (in.instagram() != null && b.instagram == null) b.instagram = in.instagram();
            brands.save(b);
            String page = in.source() == Kind.WEBSITE ? in.website() + "/contact" : null;
            contacts.add(b.id, ContactService.Found.of(in.email(), null, in.source(), page)).ifPresent(c -> {
                if (in.replies() == 0) return;
                BrandContact saved = contactRepo.findById(c.id).orElseThrow();
                saved.replies = in.replies();
                saved.lastRepliedAt = now.minusDays(3);
                ContactService.rescore(saved, now);
                contactRepo.save(saved);
            });
        }
    }
}
