package com.creatorcrm.digest;

import static org.assertj.core.api.Assertions.assertThat;

import com.creatorcrm.domain.Activity;
import com.creatorcrm.domain.Brand;
import com.creatorcrm.domain.Enums.Compensation;
import com.creatorcrm.domain.Enums.OpportunityStatus;
import com.creatorcrm.domain.Enums.OpportunityType;
import com.creatorcrm.domain.Enums.Origin;
import com.creatorcrm.domain.Enums.Priority;
import com.creatorcrm.domain.Enums.TaskStatus;
import com.creatorcrm.domain.Enums.TaskType;
import com.creatorcrm.domain.Opportunity;
import com.creatorcrm.domain.Task;
import com.creatorcrm.repo.ActivityRepo;
import com.creatorcrm.repo.BrandRepo;
import com.creatorcrm.repo.OpportunityRepo;
import com.creatorcrm.repo.TaskRepo;
import com.creatorcrm.settings.SettingsService;
import com.creatorcrm.workflow.WorkflowEngine;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** The Day summary: what she did today (with the brand), what is still waiting, new deals and tomorrow. */
@SpringBootTest
@ActiveProfiles("test")
class EndOfDayIntegrationTest {

    @Autowired DigestService digest;
    @Autowired BrandRepo brands;
    @Autowired OpportunityRepo opportunities;
    @Autowired TaskRepo tasks;
    @Autowired ActivityRepo activity;
    @Autowired WorkflowEngine workflow;
    @Autowired SettingsService settings;

    private Opportunity deal(Compensation comp) {
        Brand b = new Brand();
        b.name = "Brand " + UUID.randomUUID().toString().substring(0, 8);
        b.nameKey = Brand.key(b.name);
        b.createdAt = OffsetDateTime.now();
        b = brands.save(b);
        Opportunity o = new Opportunity();
        o.brandId = b.id;
        o.origin = Origin.INBOUND;
        o.type = OpportunityType.PAID;
        o.compensation = comp;
        o.status = OpportunityStatus.NEGOTIATING;
        o.budgetText = "$700";
        o.createdAt = OffsetDateTime.now();
        o.updatedAt = o.createdAt;
        return opportunities.save(o);
    }

    private Task task(Opportunity o, String description, int dueInDays) {
        Task t = new Task();
        t.opportunityId = o.id;
        t.type = TaskType.REPLY;
        t.description = description;
        t.priority = Priority.MEDIUM;
        t.dueDate = settings.today().plusDays(dueInDays);
        t.status = TaskStatus.OPEN;
        t.createdAt = OffsetDateTime.now();
        return tasks.save(t);
    }

    @Test
    void groupsTheDayWithBrandsAndActions() {
        Opportunity o = deal(Compensation.PAID);
        String brand = brands.findById(o.brandId).orElseThrow().name;
        workflow.completeTask(task(o, "Send rates " + o.id, 0));
        activity.save(Activity.of(o.id, Activity.FOLLOWUP_SENT, "Follow-up #1 sent"));
        activity.save(Activity.of(o.id, Activity.STATUS_CHANGED, "Not an accomplishment"));
        Task stillOpen = task(o, "Reply about dates " + o.id, -1);
        Task tomorrow = task(o, "Confirm shoot " + o.id, 1);

        DigestService.EndOfDay e = digest.endOfDay();

        assertThat(e.done()).anySatisfy(d -> {
            assertThat(d.kind()).isEqualTo("TASK");
            assertThat(d.text()).isEqualTo("Send rates " + o.id);
            assertThat(d.brand()).isEqualTo(brand);
            assertThat(d.opportunityId()).isEqualTo(o.id);
        });
        assertThat(e.done()).anySatisfy(d -> {
            assertThat(d.kind()).isEqualTo("SENT");
            assertThat(d.brand()).isEqualTo(brand);
        });
        assertThat(e.done()).noneMatch(d -> d.text().equals("Not an accomplishment"));
        assertThat(e.headline()).contains(e.done().size() + " thing");

        assertThat(e.pending()).anyMatch(i -> stillOpen.id.equals(i.refId()) && i.overdueDays() == 1);
        assertThat(e.tomorrowItems()).anyMatch(i -> tomorrow.id.equals(i.refId()));
        assertThat(e.newDeals()).anySatisfy(d -> {
            assertThat(d.opportunityId()).isEqualTo(o.id);
            assertThat(d.compensation()).isEqualTo("PAID");
            assertThat(d.budget()).isEqualTo("$700");
        });
        assertThat(digest.endOfDayText()).contains("Send rates " + o.id);
    }
}
