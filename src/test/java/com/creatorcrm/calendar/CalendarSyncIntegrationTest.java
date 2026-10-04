package com.creatorcrm.calendar;

import static org.assertj.core.api.Assertions.assertThat;

import com.creatorcrm.FakeLlm;
import com.creatorcrm.calendar.CalendarGateway.State;
import com.creatorcrm.domain.Brand;
import com.creatorcrm.domain.Deadline;
import com.creatorcrm.domain.Enums.Compensation;
import com.creatorcrm.domain.Enums.DeadlineType;
import com.creatorcrm.domain.Enums.OpportunityStatus;
import com.creatorcrm.domain.Enums.OpportunityType;
import com.creatorcrm.domain.Enums.Origin;
import com.creatorcrm.domain.Opportunity;
import com.creatorcrm.repo.BrandRepo;
import com.creatorcrm.repo.DeadlineRepo;
import com.creatorcrm.repo.OpportunityRepo;
import com.creatorcrm.settings.SettingsService;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

/** Deal deadlines on the Creator CRM calendar, kept in step as deals change, and exclusivity clashes between deals. */
@SpringBootTest
@ActiveProfiles("test")
class CalendarSyncIntegrationTest {

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        FakeLlm fakeLlm() {
            return new FakeLlm();
        }

        @Bean
        @Primary
        FakeCalendar fakeCalendar() {
            return new FakeCalendar();
        }
    }

    @Autowired FakeCalendar google;
    @Autowired CalendarSync calendar;
    @Autowired Exclusivity exclusivity;
    @Autowired BrandRepo brands;
    @Autowired OpportunityRepo opportunities;
    @Autowired DeadlineRepo deadlines;
    @Autowired SettingsService settings;

    @BeforeEach
    void reset() {
        google.state = State.READY;
        google.failWith = null;
    }

    @AfterEach
    void restore() {
        google.failWith = null;
        settings.update(Map.of(SettingsService.CALENDAR_SYNC, "true"));
    }

    private Opportunity deal(String brandName, OpportunityStatus status, String usageRights) {
        Brand b = new Brand();
        b.name = brandName;
        b.nameKey = Brand.key(brandName) + UUID.randomUUID().toString().substring(0, 6);
        b.createdAt = OffsetDateTime.now();
        brands.save(b);
        Opportunity o = new Opportunity();
        o.brandId = b.id;
        o.status = status;
        o.origin = Origin.INBOUND;
        o.type = OpportunityType.PAID;
        o.compensation = Compensation.PAID;
        o.deliverables = "1 Reel";
        o.usageRights = usageRights;
        o.campaign = "Autumn";
        o.createdAt = OffsetDateTime.now();
        o.updatedAt = OffsetDateTime.now();
        return opportunities.save(o);
    }

    private Deadline deadline(Opportunity o, DeadlineType type, LocalDate date) {
        Deadline d = new Deadline();
        d.opportunityId = o.id;
        d.type = type;
        d.dueDate = date;
        d.description = "Post the Reel";
        return deadlines.save(d);
    }

    private Deadline reload(Deadline d) {
        return deadlines.findById(d.id).orElseThrow();
    }

    @Test
    void deadlinesBecomeEventsAndFollowTheDeal() {
        LocalDate today = settings.today();
        Opportunity o = deal("Calendar Co " + UUID.randomUUID().toString().substring(0, 4), OpportunityStatus.SCHEDULED_TO_POST, "");
        Deadline post = deadline(o, DeadlineType.POSTING, today.plusDays(3));
        Deadline done = deadline(o, DeadlineType.CONTENT_DUE, today.minusDays(1));
        done.done = true;
        deadlines.save(done);
        Deadline longAgo = deadline(o, DeadlineType.CONTRACT, today.minusDays(40));

        calendar.sync();
        Deadline p = reload(post);
        assertThat(p.calendarEventId).isNotNull();
        CalendarGateway.Event e = google.events.get(p.calendarEventId);
        assertThat(e.title()).isEqualTo("📸 Post: " + calendarBrand(o));
        assertThat(e.date()).isEqualTo(today.plusDays(3));
        assertThat(e.description()).contains("Post the Reel").contains("Campaign: Autumn").contains("Added by Creator CRM");
        assertThat(reload(done).calendarEventId).isNull();
        assertThat(reload(longAgo).calendarEventId).isNull();

        // Nothing changed: no calls to Google.
        int puts = google.puts;
        calendar.sync();
        assertThat(google.puts).isEqualTo(puts);

        // A new date updates the same event.
        p.dueDate = today.plusDays(6);
        deadlines.save(p);
        calendar.sync();
        assertThat(reload(post).calendarEventId).isEqualTo(p.calendarEventId);
        assertThat(google.events.get(p.calendarEventId).date()).isEqualTo(today.plusDays(6));

        // She deleted the calendar in Google: a new one is made and the dates go back on it.
        google.calendars.clear();
        google.events.clear();
        int made = google.calendarsCreated;
        calendar.sync();
        assertThat(google.calendarsCreated).isEqualTo(made + 1);
        String id = reload(post).calendarEventId;
        assertThat(google.events).containsKey(id);

        // Closing the deal takes its dates off the calendar.
        o.status = OpportunityStatus.CLOSED;
        opportunities.save(o);
        calendar.sync();
        assertThat(reload(post).calendarEventId).isNull();
        assertThat(google.events).doesNotContainKey(id);
    }

    @Test
    void doneDatesAndTurningItOffRemoveEvents() {
        Opportunity o = deal("Off Switch " + UUID.randomUUID().toString().substring(0, 4), OpportunityStatus.CONTENT_TO_CREATE, "");
        Deadline due = deadline(o, DeadlineType.CONTENT_DUE, settings.today().plusDays(2));
        Deadline pay = deadline(o, DeadlineType.PAYMENT, settings.today().plusDays(30));
        calendar.sync();
        String dueEvent = reload(due).calendarEventId;
        assertThat(google.events).containsKey(dueEvent);

        Deadline d = reload(due);
        d.done = true;
        deadlines.save(d);
        calendar.sync();
        assertThat(google.events).doesNotContainKey(dueEvent);
        assertThat(reload(pay).calendarEventId).isNotNull();

        settings.update(Map.of(SettingsService.CALENDAR_SYNC, "false"));
        calendar.sync();
        assertThat(google.events).isEmpty();
        assertThat(deadlines.findAll()).allMatch(x -> x.calendarEventId == null);
        assertThat(calendar.status().on()).isFalse();
        assertThat(calendar.status().events()).isZero();
    }

    @Test
    void googleProblemsAreShownNotThrown() {
        Opportunity o = deal("Broken Co " + UUID.randomUUID().toString().substring(0, 4), OpportunityStatus.CONTENT_TO_CREATE, "");
        Deadline d = deadline(o, DeadlineType.CONTENT_DUE, settings.today().plusDays(2));

        google.state = State.NEEDS_RECONNECT;
        assertThat(calendar.sync()).isEqualTo(CalendarSync.Result.NONE);
        assertThat(reload(d).calendarEventId).isNull();
        assertThat(calendar.status().state()).isEqualTo("NEEDS_RECONNECT");

        google.state = State.READY;
        google.failWith = new CalendarGateway.Failure("Turn on the Google Calendar API", false, null);
        assertThat(calendar.sync()).isEqualTo(CalendarSync.Result.NONE);
        assertThat(calendar.status().error()).isEqualTo("Turn on the Google Calendar API");

        google.failWith = null;
        calendar.sync();
        assertThat(reload(d).calendarEventId).isNotNull();
        assertThat(calendar.status().error()).isNull();
    }

    @Test
    void exclusivityClashShowsOnBothDeals() {
        String tag = UUID.randomUUID().toString().substring(0, 4);
        Opportunity tea = deal("Leaf Tea " + tag, OpportunityStatus.CONTENT_TO_CREATE, "2 months exclusivity for tea brands");
        deadline(tea, DeadlineType.POSTING, settings.today().plusDays(10));
        Opportunity coffee = deal("Bean Coffee " + tag, OpportunityStatus.NEGOTIATING, "");
        deadline(coffee, DeadlineType.POSTING, settings.today().plusDays(30));
        Opportunity later = deal("Far Later " + tag, OpportunityStatus.CONTENT_TO_CREATE, "");
        deadline(later, DeadlineType.POSTING, settings.today().plusMonths(4));

        assertThat(exclusivity.forDeal(coffee.id)).filteredOn(x -> x.otherOpportunityId().equals(tea.id)).singleElement()
                .extracting(Exclusivity.Overlap::text).asString()
                .contains("falls inside your exclusivity with Leaf Tea " + tag);
        assertThat(exclusivity.forDeal(tea.id)).extracting(Exclusivity.Overlap::otherOpportunityId).contains(coffee.id).doesNotContain(later.id);
        assertThat(exclusivity.all()).anyMatch(s -> s.contains("Leaf Tea " + tag) && s.contains("Bean Coffee " + tag));
    }

    private String calendarBrand(Opportunity o) {
        return brands.findById(o.brandId).orElseThrow().name;
    }
}
