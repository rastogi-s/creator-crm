package com.creatorcrm.news;

import com.creatorcrm.channels.instagram.BrandLookupService;
import com.creatorcrm.contacts.Emails;
import com.creatorcrm.contacts.WebsiteReader;
import com.creatorcrm.domain.Brand;
import com.creatorcrm.domain.BrandDomain;
import com.creatorcrm.domain.BrandNews;
import com.creatorcrm.domain.Message;
import com.creatorcrm.llm.LlmClient;
import com.creatorcrm.llm.NewsFound;
import com.creatorcrm.llm.NewsSearchInput;
import com.creatorcrm.llm.NewsSort;
import com.creatorcrm.llm.NewsSortInput;
import com.creatorcrm.llm.OutOfCreditsException;
import com.creatorcrm.llm.SearchDepth;
import com.creatorcrm.repo.BrandDomainRepo;
import com.creatorcrm.repo.BrandNewsRepo;
import com.creatorcrm.repo.BrandRepo;
import com.creatorcrm.repo.MessageRepo;
import com.creatorcrm.settings.SettingsService;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * Brand news for re-pitches: what brands she has worked with have launched, teased or restocked lately, so the next
 * re-pitch opens with a real reason to talk. Free sources first (the brand's own emails in her inbox, its Instagram
 * posts, its website feeds, a public news index); Claude web search only for a brand those turned up nothing for.
 * Claude then sorts what was found into news and noise, and scores how well each item fits her.
 */
@Service
public class BrandNewsService {
    private static final Logger log = LoggerFactory.getLogger(BrandNewsService.class);

    /** News older than this is no reason to write "I just saw…". */
    public static final int FRESH_DAYS = 45;
    /** A brand's sources are read at most this often. */
    static final int RECHECK_DAYS = 7;
    /** Brands checked in one morning run: website reads pause between pages, so the rest wait a day. */
    static final int MAX_BRANDS_PER_RUN = 6;
    /** Items sent to Claude in one sort. */
    static final int MAX_SORT = 15;
    /** Below this fit, an item is hidden; at or above {@link #MIN_FIT_TO_USE} it can open a re-pitch. */
    static final int HIDE_BELOW = 40;
    static final int MIN_FIT_TO_USE = 50;
    static final int MAX_SNIPPET = 1500;

    /** One item as the app shows it. {@code date} is when the brand published it, or when the app found it. */
    public record View(Long id, Long brandId, String source, String title, String headline, String pitchIdea, String kind,
                       Integer fit, String url, Long messageId, OffsetDateTime date, boolean hidden, boolean used) {}

    /** What a check found: new items, everything recent for the brand, and sources that couldn't be read. */
    public record CheckResult(int added, List<View> news, List<String> notes) {}

    private final BrandRepo brands;
    private final BrandDomainRepo domains;
    private final BrandNewsRepo repo;
    private final MessageRepo messages;
    private final BrandLookupService instagram;
    private final WebsiteReader website;
    private final NewsIndex newsIndex;
    private final LlmClient llm;
    private final SettingsService settings;

    public BrandNewsService(BrandRepo brands, BrandDomainRepo domains, BrandNewsRepo repo, MessageRepo messages,
                            BrandLookupService instagram, WebsiteReader website, NewsIndex newsIndex, LlmClient llm,
                            SettingsService settings) {
        this.brands = brands;
        this.domains = domains;
        this.repo = repo;
        this.messages = messages;
        this.instagram = instagram;
        this.website = website;
        this.newsIndex = newsIndex;
        this.llm = llm;
        this.settings = settings;
    }

    /**
     * Morning run: checks the given brands (best re-pitch candidates first) whose news is a week old or was never
     * read, a few per run. Returns how many brands were checked.
     */
    public int refreshDue(List<Long> brandIds) {
        if (!settings.brandNews()) return 0;
        OffsetDateTime stale = OffsetDateTime.now().minusDays(RECHECK_DAYS);
        int n = 0;
        for (Long id : new LinkedHashSet<>(brandIds)) {
            if (n >= MAX_BRANDS_PER_RUN) break;
            Brand b = brands.findById(id).orElse(null);
            if (b == null || (b.newsCheckedAt != null && b.newsCheckedAt.isAfter(stale))) continue;
            try {
                check(b);
            } catch (OutOfCreditsException e) {
                log.warn("Brand news: Claude is out of credits; stopping for today");
                break;
            } catch (RuntimeException e) {
                log.warn("Could not check {}'s news: {}", b.name, e.getMessage());
            }
            n++;
        }
        return n;
    }

    /** "Check for news" on a brand: reads every source now, whenever it was last checked. */
    public CheckResult check(Long brandId) {
        Brand b = brands.findById(brandId).orElseThrow(() -> new IllegalArgumentException("That brand no longer exists"));
        return check(b);
    }

    CheckResult check(Brand b) {
        OffsetDateTime since = OffsetDateTime.now().minusDays(FRESH_DAYS);
        List<String> notes = new ArrayList<>();
        int added = collect(b, since, notes);
        sort(b);
        // Re-read: a re-pitch may have been drafted for this brand while its websites were being read
        brands.findById(b.id).ifPresent(fresh -> {
            fresh.newsCheckedAt = OffsetDateTime.now();
            brands.save(fresh);
        });
        log.info("Brand news for {}: {} new item(s){}", b.name, added, notes.isEmpty() ? "" : " (" + String.join("; ", notes) + ")");
        return new CheckResult(added, list(b.id), notes);
    }

    /** The brand's news from the last few months, newest first. */
    public List<View> list(Long brandId) {
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(FRESH_DAYS * 2L);
        return repo.findByBrandId(brandId).stream().filter(n -> n.date().isAfter(cutoff))
                .sorted(Comparator.comparing(BrandNews::date).reversed()).map(BrandNewsService::view).toList();
    }

    /** The fresh, sorted, on-niche item that should open this brand's next re-pitch, if it has one. */
    public Optional<BrandNews> best(Long brandId, LocalDate today) {
        if (!settings.brandNews()) return Optional.empty();
        OffsetDateTime since = today.minusDays(FRESH_DAYS).atStartOfDay(settings.zone()).toOffsetDateTime();
        return repo.findByBrandId(brandId).stream()
                .filter(n -> !n.hidden && n.usedInDraftId == null && n.kind != null && n.kind != BrandNews.Kind.OTHER)
                .filter(n -> n.fit != null && n.fit >= MIN_FIT_TO_USE && n.headline != null && !n.headline.isBlank())
                .filter(n -> n.date().isAfter(since))
                .max(Comparator.comparing((BrandNews n) -> n.fit).thenComparing(BrandNews::date));
    }

    public void markUsed(BrandNews n, Long draftId) {
        n.usedInDraftId = draftId;
        repo.save(n);
    }

    /** The news a re-pitch draft opened with, for the "Why now" box in Drafts. */
    public Optional<View> forDraft(Long draftId) {
        return repo.findFirstByUsedInDraftId(draftId).map(BrandNewsService::view);
    }

    /** She hid it ("not relevant"), or showed it again. */
    public View setHidden(Long id, boolean hidden) {
        BrandNews n = repo.findById(id).orElseThrow(() -> new IllegalArgumentException("That news item no longer exists"));
        n.hidden = hidden;
        return view(repo.save(n));
    }

    // ---------------------------------------------------------------- collecting

    private int collect(Brand b, OffsetDateTime since, List<String> notes) {
        int added = 0;

        // 1. The brand's own emails in her inbox: newsletters, launch announcements, gifting invites. Free, already synced.
        for (String d : domainsOf(b)) {
            for (Message m : messages.brandMailSince("%@" + d, "%." + d, since, PageRequest.of(0, 20))) {
                String subject = m.subject == null || m.subject.isBlank() ? "Email from " + b.name : m.subject.strip();
                added += save(b, BrandNews.Source.EMAIL, "email:" + m.id, subject, m.content, null, m.id, m.sentAt);
            }
        }

        // 2. Its Instagram posts, through Meta's official business discovery. Teasers usually show up here first.
        if (b.instagram != null && !b.instagram.isBlank() && instagram.available()) {
            try {
                for (BrandLookupService.Post p : instagram.recentPosts(b.instagram, 12)) {
                    if (p.postedAt() == null || p.postedAt().isBefore(since) || p.caption().isBlank()) continue;
                    String key = "ig:" + (p.permalink().isBlank() ? p.postedAt() : p.permalink());
                    added += save(b, BrandNews.Source.INSTAGRAM, key, firstLine(p.caption(), 150), p.caption(),
                            p.permalink().isBlank() ? null : p.permalink(), null, p.postedAt());
                }
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                notes.add("Instagram: " + e.getMessage());
            }
        }

        // 3. Its website's news and product feeds, read politely (robots.txt, a pause between requests).
        if (b.website != null && !b.website.isBlank()) {
            WebsiteReader.Feeds f = website.feeds(b.website);
            if (f.problem() != null && f.items().isEmpty()) notes.add("Website: " + f.problem());
            for (FeedReader.Item it : f.items()) {
                if (it.published() == null || it.published().isBefore(since) || it.link() == null) continue;
                added += save(b, BrandNews.Source.WEBSITE, "web:" + it.link(), it.title(), it.summary(), it.link(), null, it.published());
            }
        }

        // 4. News articles naming the brand, from a free public index.
        for (NewsIndex.Article a : newsIndex.search(b.name, FRESH_DAYS)) {
            if (a.seenAt() != null && a.seenAt().isBefore(since)) continue;
            added += save(b, BrandNews.Source.NEWS, "news:" + a.url(), a.title(), a.title() + (a.site().isBlank() ? "" : " (" + a.site() + ")"),
                    a.url(), null, a.seenAt());
        }

        // 5. Nothing at all from the free sources: a quick Claude web search, if she allows it.
        boolean anyFresh = repo.findByBrandId(b.id).stream().anyMatch(n -> !n.hidden && n.date().isAfter(since));
        if (!anyFresh && settings.brandNewsWebSearch() && llm.isConfigured()) {
            try {
                NewsFound found = llm.searchBrandNews(new NewsSearchInput(settings.today(), b.name, b.website, b.instagram,
                        FRESH_DAYS, SearchDepth.QUICK));
                for (NewsFound.Item it : found.items() == null ? List.<NewsFound.Item>of() : found.items()) {
                    if (it.url() == null || !it.url().matches("(?i)https?://\\S+") || it.title() == null || it.title().isBlank()) continue;
                    OffsetDateTime when = day(it.date());
                    if (when != null && when.isBefore(since)) continue;
                    added += save(b, BrandNews.Source.WEB_SEARCH, "search:" + it.url(), it.title(), it.summary(), it.url(), null, when);
                }
            } catch (OutOfCreditsException e) {
                throw e;
            } catch (RuntimeException e) {
                notes.add("Web search: " + e.getMessage());
            }
        }
        return added;
    }

    /** The domains the brand writes from: its known email domains, its website's and its contact's. */
    private List<String> domainsOf(Brand b) {
        Set<String> out = new LinkedHashSet<>();
        for (BrandDomain d : domains.findByBrandId(b.id)) if (d.domain != null) out.add(d.domain.toLowerCase());
        String site = Emails.domainOfUrl(b.website);
        if (site != null && !WebsiteReader.neverRead(site)) out.add(site);
        String mail = Emails.brandDomain(b.contactEmail);
        if (mail != null) out.add(mail);
        out.removeIf(d -> d.isBlank() || Emails.isFreeMail(d) || !d.matches("[a-z0-9.-]+"));
        return new ArrayList<>(out);
    }

    /** 1 if stored, 0 if the brand already has it. */
    private int save(Brand b, BrandNews.Source source, String key, String title, String text, String url, Long messageId,
                     OffsetDateTime published) {
        String k = cut(key, 600);
        if (repo.existsByBrandIdAndItemKey(b.id, k)) return 0;
        BrandNews n = new BrandNews();
        n.brandId = b.id;
        n.source = source;
        n.itemKey = k;
        n.title = cut(title.strip(), 500);
        n.snippet = text == null ? null : cut(text.replaceAll("\\s+", " ").strip(), MAX_SNIPPET);
        n.url = url == null ? null : cut(url, 2000);
        n.messageId = messageId;
        n.publishedAt = published;
        n.foundAt = OffsetDateTime.now();
        try {
            repo.save(n);
            return 1;
        } catch (DataIntegrityViolationException e) {
            return 0; // found at the same moment by another check
        }
    }

    // ---------------------------------------------------------------- sorting

    /** Claude labels what's new and unsorted; without Claude, items wait unsorted and can't open a re-pitch. */
    private void sort(Brand b) {
        if (!llm.isConfigured()) return;
        OffsetDateTime since = OffsetDateTime.now().minusDays(FRESH_DAYS);
        List<BrandNews> todo = repo.findByBrandId(b.id).stream()
                .filter(n -> n.kind == null && !n.hidden && n.date().isAfter(since))
                .sorted(Comparator.comparing(BrandNews::date).reversed()).limit(MAX_SORT).toList();
        if (todo.isEmpty()) return;
        List<NewsSortInput.Item> items = todo.stream().map(n -> new NewsSortInput.Item(n.id, n.source.name(),
                n.date().toLocalDate().toString(), n.title, n.snippet == null ? "" : n.snippet)).toList();
        NewsSort sorted = llm.sortBrandNews(new NewsSortInput(settings.today(), b.name, "", items));
        Map<Long, NewsSort.Verdict> byId = (sorted.items() == null ? List.<NewsSort.Verdict>of() : sorted.items()).stream()
                .collect(Collectors.toMap(NewsSort.Verdict::id, Function.identity(), (x, y) -> x));
        for (BrandNews n : todo) {
            NewsSort.Verdict v = byId.get(n.id);
            if (v == null || !v.isNews() || v.kind() == null || v.kind() == BrandNews.Kind.OTHER) {
                n.kind = BrandNews.Kind.OTHER;
                n.fit = 0;
                n.hidden = true;
            } else {
                n.kind = v.kind();
                n.headline = blankToNull(cut(v.headline(), 300));
                n.pitchIdea = blankToNull(cut(v.pitchIdea(), 500));
                n.fit = Math.max(0, Math.min(100, v.fit()));
                n.hidden = n.fit < HIDE_BELOW || n.headline == null;
            }
            repo.save(n);
        }
    }

    // ---------------------------------------------------------------- helpers

    static View view(BrandNews n) {
        return new View(n.id, n.brandId, n.source.name(), n.title, n.headline, n.pitchIdea, n.kind == null ? null : n.kind.name(),
                n.fit, n.url, n.messageId, n.date(), n.hidden, n.usedInDraftId != null);
    }

    static String firstLine(String s, int max) {
        String line = s.strip().lines().filter(l -> !l.isBlank()).findFirst().orElse("").strip();
        return cut(line, max);
    }

    private static OffsetDateTime day(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s.strip().length() > 10 ? s.strip().substring(0, 10) : s.strip()).atStartOfDay().atOffset(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String cut(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
