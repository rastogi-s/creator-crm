package com.creatorcrm.contacts;

import static org.assertj.core.api.Assertions.assertThat;

import com.creatorcrm.domain.Brand;
import com.creatorcrm.domain.BrandContact;
import com.creatorcrm.domain.BrandContact.Role;
import com.creatorcrm.domain.ContactSource;
import com.creatorcrm.domain.Conversation;
import com.creatorcrm.domain.Draft;
import com.creatorcrm.domain.Enums.Compensation;
import com.creatorcrm.domain.Enums.DealStage;
import com.creatorcrm.domain.Enums.Direction;
import com.creatorcrm.domain.Enums.DraftType;
import com.creatorcrm.domain.Enums.OpportunityStatus;
import com.creatorcrm.domain.Enums.OpportunityType;
import com.creatorcrm.domain.Enums.Origin;
import com.creatorcrm.domain.Enums.Platform;
import com.creatorcrm.domain.Message;
import com.creatorcrm.domain.Opportunity;
import com.creatorcrm.domain.Suppression;
import com.creatorcrm.drafts.DraftService;
import com.creatorcrm.repo.BrandContactRepo;
import com.creatorcrm.repo.BrandRepo;
import com.creatorcrm.repo.ConversationRepo;
import com.creatorcrm.repo.MessageRepo;
import com.creatorcrm.repo.OpportunityRepo;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** Brand contacts end to end: built from email threads, ranked, deduped, imported, merged, and kept out of outreach. */
@SpringBootTest
@ActiveProfiles("test")
class ContactsIntegrationTest {

    @Autowired ContactService contacts;
    @Autowired GmailContacts gmail;
    @Autowired ContactCsv csv;
    @Autowired Suppressions suppressions;
    @Autowired DraftService drafts;
    @Autowired BrandRepo brands;
    @Autowired BrandContactRepo contactRepo;
    @Autowired ConversationRepo conversations;
    @Autowired MessageRepo messages;
    @Autowired OpportunityRepo opportunities;

    private static String tag() {
        return UUID.randomUUID().toString().substring(0, 6);
    }

    private Brand brand(String name, String website) {
        Brand b = new Brand();
        b.name = name;
        b.nameKey = Brand.key(name);
        b.website = website;
        b.createdAt = OffsetDateTime.now();
        return brands.save(b);
    }

    private Conversation thread(Brand b, String counterparty) {
        Conversation c = new Conversation();
        c.brandId = b.id;
        c.platform = Platform.EMAIL;
        c.externalId = "t-" + UUID.randomUUID();
        c.counterparty = counterparty;
        c.brandRelated = true;
        c.createdAt = OffsetDateTime.now();
        return conversations.save(c);
    }

    private void message(Conversation c, Direction dir, String from, String fromName, String to, String body, OffsetDateTime at) {
        Message m = new Message();
        m.conversationId = c.id;
        m.externalId = "m-" + UUID.randomUUID();
        m.direction = dir;
        m.sender = from;
        m.senderName = fromName;
        m.recipient = to;
        m.content = body;
        m.sentAt = at;
        m.aiProcessed = true; // already read, so other tests' ingestion runs never pick these up
        messages.save(m);
    }

    @Test
    void buildsRankedContactsFromEmailThreads() {
        String t = tag();
        String domain = "glow" + t + ".com";
        Brand b = brand("Glow " + t, "https://www." + domain);
        String me = "creator" + t + "@gmail.com";
        String jane = "jane@" + domain;
        String sam = "sam@" + domain;
        String collabs = "collabs@" + domain;
        OffsetDateTime start = OffsetDateTime.now().minusDays(20);

        // Jane answered within hours and the deal went ahead
        Conversation one = thread(b, jane);
        message(one, Direction.OUTBOUND, me, "Me", "Jane <" + jane + ">", "Hi Jane, pitch", start);
        message(one, Direction.INBOUND, jane, "Jane Doe", me,
                "Love it!\n\nJane Doe | Influencer Marketing Manager\n+1 415 555 0134", start.plusHours(3));
        Opportunity o = new Opportunity();
        o.brandId = b.id;
        o.conversationId = one.id;
        o.origin = Origin.PITCH;
        o.type = OpportunityType.values()[0];
        o.compensation = Compensation.values()[0];
        o.status = OpportunityStatus.NEGOTIATING;
        o.stage = DealStage.CONTRACT;
        o.createdAt = start;
        o.updatedAt = start;
        opportunities.save(o);

        // Sam answered once, days later
        Conversation two = thread(b, sam);
        message(two, Direction.OUTBOUND, me, "Me", sam, "Hi Sam", start.plusDays(1));
        message(two, Direction.INBOUND, sam, "Sam Lee", me, "Maybe next quarter.", start.plusDays(5));

        // collabs@ never answered
        Conversation three = thread(b, collabs);
        message(three, Direction.OUTBOUND, me, "Me", collabs, "Hello team", start.plusDays(2));

        gmail.refresh();

        List<BrandContact> ranked = contactRepo.findByBrandIdOrderByScoreDescIdAsc(b.id);
        assertThat(ranked).extracting(c -> c.email).containsExactly(jane, sam, collabs);
        BrandContact j = ranked.get(0);
        assertThat(j.replies).isEqualTo(1);
        assertThat(j.dealsWon).isEqualTo(1);
        assertThat(j.avgReplyHours).isEqualTo(3);
        assertThat(j.title).isEqualTo("Influencer Marketing Manager");
        assertThat(j.role).isEqualTo(Role.PARTNERSHIPS);
        assertThat(j.name).isEqualTo("Jane Doe");
        assertThat(ranked.get(2).emailsSent).isEqualTo(1);
        assertThat(contactRepo.findByEmail(me)).isEmpty();
        assertThat(brands.findById(b.id).orElseThrow().contactEmail).isEqualTo(jane);

        // Running again changes nothing
        gmail.refresh();
        assertThat(contactRepo.findByBrandIdOrderByScoreDescIdAsc(b.id)).hasSize(3);
    }

    @Test
    void sameAddressIsNeverSavedTwiceAndDetailsMerge() {
        String t = tag();
        Brand b = brand("Merge " + t, null);
        String email = "PR@merge" + t + ".com";
        contacts.add(b.id, ContactService.Found.of(email, null, ContactSource.Kind.WEBSITE, "https://merge" + t + ".com/press"));
        contacts.add(b.id, new ContactService.Found(email.toLowerCase(), "Pat", "Head of PR", null, "555 0100 222", null, null,
                null, ContactSource.Kind.IMPORT, null));
        List<ContactService.View> views = contacts.forBrand(b.id);
        assertThat(views).hasSize(1);
        BrandContact c = views.get(0).contact();
        assertThat(c.name).isEqualTo("Pat");
        assertThat(c.title).isEqualTo("Head of PR");
        assertThat(c.role).isEqualTo(Role.PR);
        assertThat(views.get(0).sources()).containsExactlyInAnyOrder("WEBSITE", "IMPORT");
        assertThat(contacts.add(b.id, ContactService.Found.of("noreply@merge" + t + ".com", null, ContactSource.Kind.WEBSITE, null))).isEmpty();
    }

    @Test
    void doNotEmailStopsPitchesAndForgetKeepsThemOut() {
        String t = tag();
        Brand b = brand("Stop " + t, null);
        String email = "hello@stop" + t + ".com";
        BrandContact c = contacts.add(b.id, ContactService.Found.of(email, null, ContactSource.Kind.MANUAL, null)).orElseThrow();
        assertThat(brands.findById(b.id).orElseThrow().contactEmail).isEqualTo(email);

        Draft pitch = new Draft();
        pitch.type = DraftType.PITCH;
        pitch.channel = Platform.EMAIL;
        pitch.toAddress = email;
        assertThat(drafts.sendBlockedReason(pitch)).isPresent().get().asString().doesNotContain("asked not to be emailed");

        contacts.doNotEmail(c.id, Suppression.Reason.OPTED_OUT);
        assertThat(drafts.sendBlockedReason(pitch)).isPresent().get().asString().contains("asked not to be emailed");
        assertThat(brands.findById(b.id).orElseThrow().contactEmail).isNull();
        Draft reply = new Draft();
        reply.type = DraftType.REPLY;
        reply.channel = Platform.EMAIL;
        reply.toAddress = email;
        assertThat(drafts.sendBlockedReason(reply).orElse("")).doesNotContain("asked not to be emailed");

        contacts.forget(c.id);
        assertThat(contactRepo.findByEmail(email)).isEmpty();
        assertThat(contacts.add(b.id, ContactService.Found.of(email, null, ContactSource.Kind.IMPORT, null))).isEmpty();
        assertThat(suppressions.blocked(email)).isTrue();
    }

    @Test
    void importsSpreadsheetsWithPreviewAndKeepsWhereTheyCameFrom() {
        String t = tag();
        Brand existing = brand("Known " + t, "https://known" + t + ".com");
        String file = "Company,Website,Email,First Name,Last Name,Job Title\n"
                + "Known " + t + ",known" + t + ".com,ana@known" + t + ".com,Ana,Ruiz,Brand Partnerships Lead\n"
                + ",https://fresh" + t + ".io,collabs@fresh" + t + ".io,,,\n"
                + "Bad,,not-an-email,,,\n"
                + "Dup,,ana@known" + t + ".com,,,\n";
        ContactCsv.Preview p = csv.preview(file);
        assertThat(p.added()).isEqualTo(2);
        assertThat(p.newBrands()).isEqualTo(1);
        assertThat(p.skipped()).isEqualTo(2);
        assertThat(contactRepo.findByEmail("ana@known" + t + ".com")).isEmpty();

        csv.importCsv(file, ContactCsv.Origin.HUNTER);
        BrandContact ana = contactRepo.findByEmail("ana@known" + t + ".com").orElseThrow();
        assertThat(ana.brandId).isEqualTo(existing.id);
        assertThat(ana.name).isEqualTo("Ana Ruiz");
        assertThat(ana.role).isEqualTo(Role.PARTNERSHIPS);
        Brand fresh = brands.findByNameKey(Brand.key("Fresh" + t)).orElseThrow();
        ContactService.View v = contacts.forBrand(fresh.id).get(0);
        assertThat(v.sources()).containsExactly("HUNTER");
        assertThat(v.shareable()).isFalse(); // bought data is never shared
        assertThat(csv.export(contacts.forBrand(existing.id))).contains("ana@known" + t + ".com").contains("HUNTER");
    }

    @Test
    void mergesBrandsThatShareAWebsite() {
        String t = tag();
        Brand keep = brand("Lumi " + t, "https://lumi" + t + ".com");
        Brand dupe = brand("Lumi Skincare " + t, null);
        contacts.add(keep.id, ContactService.Found.of("pr@lumi" + t + ".com", null, ContactSource.Kind.MANUAL, null));
        contacts.claimDomain(keep.id, "lumi" + t + ".com");
        contacts.add(dupe.id, ContactService.Found.of("ana@lumi" + t + ".com", "Ana", ContactSource.Kind.GMAIL, null));
        assertThat(contacts.duplicates()).anyMatch(d -> d.brandId().equals(dupe.id) && d.otherBrandId().equals(keep.id));

        contacts.merge(keep.id, dupe.id);
        assertThat(brands.findById(dupe.id)).isEmpty();
        assertThat(contacts.forBrand(keep.id)).hasSize(2);
        assertThat(contacts.duplicates()).noneMatch(d -> d.brandId().equals(dupe.id) || d.otherBrandId().equals(dupe.id));
    }
}
