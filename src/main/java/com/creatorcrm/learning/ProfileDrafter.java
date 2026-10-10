package com.creatorcrm.learning;

import com.creatorcrm.channels.instagram.InstagramStatsService;
import com.creatorcrm.contacts.WebsiteReader;
import com.creatorcrm.domain.CreatorLink;
import com.creatorcrm.domain.Opportunity;
import com.creatorcrm.domain.WritingExample;
import com.creatorcrm.links.LinkService;
import com.creatorcrm.llm.LlmClient;
import com.creatorcrm.llm.ProfileDraft;
import com.creatorcrm.llm.ProfileDraftInput;
import com.creatorcrm.llm.Untrusted;
import com.creatorcrm.repo.BrandRepo;
import com.creatorcrm.repo.OpportunityRepo;
import com.creatorcrm.repo.WritingExampleRepo;
import com.creatorcrm.settings.SettingsService;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Settings, About you, Draft with Claude: gathers what the app already knows about her (current profile, links,
 * Instagram stats, deals and messages she wrote to brands) and asks Claude for a profile. The draft goes back to the
 * page for her to edit; it is never saved here.
 */
@Service
public class ProfileDrafter {

    static final int MAX_DEALS = 40;
    static final int MAX_MESSAGES = 8;
    static final int MAX_FETCH_DOMAINS = 5;
    private static final int MAX_FIELD_CHARS = 300;

    /** The draft, plus what it was based on, so the page can say so. */
    public record Result(String profile, List<String> toCheck, Sources sources) {}

    public record Sources(int links, int pagesAllowed, boolean instagram, int deals, int messages) {}

    private final LlmClient llm;
    private final SettingsService settings;
    private final LinkService links;
    private final InstagramStatsService instagramStats;
    private final OpportunityRepo opportunities;
    private final BrandRepo brands;
    private final WritingExampleRepo examples;

    public ProfileDrafter(LlmClient llm, SettingsService settings, LinkService links, InstagramStatsService instagramStats,
                          OpportunityRepo opportunities, BrandRepo brands, WritingExampleRepo examples) {
        this.llm = llm;
        this.settings = settings;
        this.links = links;
        this.instagramStats = instagramStats;
        this.opportunities = opportunities;
        this.brands = brands;
        this.examples = examples;
    }

    /** {@code current} is what's in the box right now (maybe unsaved); null means the saved profile. */
    public Result draft(String current) {
        ProfileDraftInput in = input(current);
        ProfileDraft d = llm.draftProfile(in);
        String profile = d.profile() == null ? "" : d.profile().strip();
        if (profile.isEmpty()) throw new IllegalStateException("Claude returned an empty profile; try again");
        List<String> toCheck = d.toCheck() == null ? List.of()
                : d.toCheck().stream().filter(Objects::nonNull).map(String::strip).filter(s -> !s.isEmpty()).limit(10).toList();
        return new Result(profile, toCheck, new Sources(in.links().size(), in.fetchDomains().size(),
                !in.instagramStats().isBlank(), in.deals().size(), in.sentMessages().size()));
    }

    ProfileDraftInput input(String current) {
        List<CreatorLink> all = links.list();
        List<String> linkLines = all.stream().map(l -> Untrusted.escape(l.label) + ": " + l.url).toList();
        return new ProfileDraftInput(settings.creatorName(), current != null ? current : settings.creatorProfile(),
                linkLines, instagramStats.profileSection(), deals(), messages(), fetchDomains(all));
    }

    /** Hosts of her own links that Claude may open: not social networks or marketplaces, which block it anyway. */
    static List<String> fetchDomains(List<CreatorLink> all) {
        Set<String> hosts = new LinkedHashSet<>();
        for (CreatorLink l : all) {
            String host;
            try {
                host = URI.create(l.url).getHost();
            } catch (RuntimeException e) {
                continue;
            }
            if (host == null || !host.contains(".") || WebsiteReader.neverRead(host)) continue;
            hosts.add(host.toLowerCase(Locale.ROOT).replaceFirst("^www\\.", ""));
            if (hosts.size() == MAX_FETCH_DOMAINS) break;
        }
        return new ArrayList<>(hosts);
    }

    List<String> deals() {
        List<Opportunity> all = opportunities.findAll().stream()
                .sorted(Comparator.comparing((Opportunity o) -> o.updatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(MAX_DEALS).toList();
        Map<Long, String> names = brands.findAllById(all.stream().map(o -> o.brandId).filter(Objects::nonNull).distinct().toList())
                .stream().collect(Collectors.toMap(b -> b.id, b -> b.name == null ? "" : b.name, (a, b) -> a));
        return all.stream().map(o -> deal(o, names.getOrDefault(o.brandId, "Unknown brand"))).toList();
    }

    static String deal(Opportunity o, String brand) {
        StringBuilder sb = new StringBuilder(clip(brand));
        sb.append(o.origin == com.creatorcrm.domain.Enums.Origin.PITCH ? " (she pitched)" : " (brand reached out)");
        if (o.type != null) sb.append("; type ").append(o.type);
        if (o.compensation != null) sb.append("; ").append(o.compensation.name().toLowerCase(Locale.ROOT));
        if (o.budgetAmount != null) {
            sb.append("; agreed or offered ").append(o.currency == null ? "" : o.currency + " ").append(o.budgetAmount.toPlainString());
        } else if (o.budgetText != null && !o.budgetText.isBlank()) {
            sb.append("; budget: ").append(clip(o.budgetText));
        }
        if (o.deliverables != null && !o.deliverables.isBlank()) sb.append("; deliverables: ").append(clip(o.deliverables));
        if (o.usageRights != null && !o.usageRights.isBlank()) sb.append("; usage rights: ").append(clip(o.usageRights));
        if (o.status != null) sb.append("; status: ").append(o.status.label);
        if (o.stage != null) sb.append(" (").append(o.stage.label).append(')');
        return sb.toString();
    }

    /** Her own words first (written outside the app, or drafts she edited), then any she sent as drafted. */
    List<String> messages() {
        if (!settings.learnFromHistory()) return List.of();
        Function<WritingExample, Integer> rank = e -> (WritingExample.WRITTEN.equals(e.source) ? 2 : 0) + (e.edited ? 1 : 0);
        return examples.findTop300ByExcludedFalseOrderBySentAtDesc().stream()
                .sorted(Comparator.comparing(rank).reversed()) // stable: newest first within each rank
                .limit(MAX_MESSAGES)
                .map(LearningService::render)
                .toList();
    }

    private static String clip(String s) {
        String v = Untrusted.escape(s.strip()).replace('\n', ' ');
        return v.length() > MAX_FIELD_CHARS ? v.substring(0, MAX_FIELD_CHARS) + "…" : v;
    }
}
