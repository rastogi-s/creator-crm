package com.creatorcrm.links;

import com.creatorcrm.domain.CreatorLink;
import com.creatorcrm.repo.CreatorLinkRepo;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The creator's links page. Drafts get these links so they can quote real URLs instead of placeholders. */
@Service
public class LinkService {

    public static final int MAX_LINKS = 50;

    /** Friendly labels for well-known hosts, used when the creator leaves the label blank. */
    private static final Map<String, String> KNOWN_HOSTS = Map.ofEntries(
            Map.entry("instagram.com", "Instagram"), Map.entry("tiktok.com", "TikTok"),
            Map.entry("youtube.com", "YouTube"), Map.entry("youtu.be", "YouTube"),
            Map.entry("x.com", "X"), Map.entry("twitter.com", "X"), Map.entry("threads.net", "Threads"),
            Map.entry("facebook.com", "Facebook"), Map.entry("pinterest.com", "Pinterest"),
            Map.entry("linkedin.com", "LinkedIn"), Map.entry("twitch.tv", "Twitch"),
            Map.entry("snapchat.com", "Snapchat"), Map.entry("linktr.ee", "Linktree"),
            Map.entry("behance.net", "Behance"), Map.entry("substack.com", "Substack"));

    private final CreatorLinkRepo links;

    public LinkService(CreatorLinkRepo links) {
        this.links = links;
    }

    public List<CreatorLink> list() {
        return links.findAllByOrderBySortOrderAscIdAsc();
    }

    @Transactional
    public CreatorLink add(String label, String url) {
        List<CreatorLink> all = list();
        if (all.size() >= MAX_LINKS) throw new IllegalArgumentException("You can save up to " + MAX_LINKS + " links");
        CreatorLink l = new CreatorLink();
        l.url = normalizeUrl(url);
        l.label = labelOrDefault(label, l.url);
        l.sortOrder = all.stream().mapToInt(x -> x.sortOrder).max().orElse(0) + 1;
        l.createdAt = OffsetDateTime.now();
        return links.save(l);
    }

    public record Added(int added, int skipped) {}

    /**
     * Add many links at once (Linktree import, pasted list), in order. Links already saved are skipped,
     * as is anything past the {@link #MAX_LINKS} limit; bad addresses fail the whole batch before anything is saved.
     */
    @Transactional
    public Added addAll(List<Map.Entry<String, String>> labelAndUrl) {
        List<CreatorLink> all = list();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (CreatorLink l : all) seen.add(sameLinkKey(l.url));
        List<CreatorLink> fresh = new ArrayList<>();
        int skipped = 0;
        int order = all.stream().mapToInt(x -> x.sortOrder).max().orElse(0);
        for (Map.Entry<String, String> in : labelAndUrl) {
            CreatorLink l = new CreatorLink();
            l.url = normalizeUrl(in.getValue());
            l.label = labelOrDefault(in.getKey(), l.url);
            if (!seen.add(sameLinkKey(l.url)) || all.size() + fresh.size() >= MAX_LINKS) { skipped++; continue; }
            l.sortOrder = ++order;
            l.createdAt = OffsetDateTime.now();
            fresh.add(l);
        }
        links.saveAll(fresh);
        return new Added(fresh.size(), skipped);
    }

    @Transactional
    public CreatorLink update(Long id, String label, String url) {
        CreatorLink l = links.findById(id).orElseThrow();
        l.url = normalizeUrl(url);
        l.label = labelOrDefault(label, l.url);
        return links.save(l);
    }

    @Transactional
    public void delete(Long id) {
        links.delete(links.findById(id).orElseThrow());
    }

    /** Swap a link with its neighbour; up = earlier in the list. */
    @Transactional
    public List<CreatorLink> move(Long id, boolean up) {
        List<CreatorLink> all = new ArrayList<>(list());
        int i = indexOf(all, id);
        int j = up ? i - 1 : i + 1;
        if (j >= 0 && j < all.size()) {
            all.add(j, all.remove(i));
            for (int k = 0; k < all.size(); k++) all.get(k).sortOrder = k + 1;
            links.saveAll(all);
        }
        return all;
    }

    /** The links as a section of the creator profile given to the draft writer. Empty when there are none. */
    public String profileSection() {
        List<CreatorLink> all = list();
        if (all.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("# My links (use these exact URLs when a link is needed)\n");
        for (CreatorLink l : all) sb.append("- ").append(l.label).append(": ").append(l.url).append('\n');
        return sb.toString();
    }

    /** Two URLs count as the same link when they differ only by scheme, "www.", host case or a trailing slash. */
    static String sameLinkKey(String url) {
        String v = url.strip().replaceFirst("(?i)^https?://", "").replaceFirst("(?i)^www\\.", "");
        int slash = v.indexOf('/');
        String host = slash < 0 ? v : v.substring(0, slash);
        String rest = slash < 0 ? "" : v.substring(slash);
        return host.toLowerCase(Locale.ROOT) + rest.replaceFirst("/+$", "");
    }

    private static int indexOf(List<CreatorLink> all, Long id) {
        for (int i = 0; i < all.size(); i++) if (all.get(i).id.equals(id)) return i;
        throw new java.util.NoSuchElementException("Unknown link");
    }

    /** Accepts "instagram.com/me" or a full http(s) URL; anything else (javascript:, mailto:...) is rejected. */
    public static String normalizeUrl(String raw) {
        String v = raw == null ? "" : raw.strip();
        if (v.isEmpty()) throw new IllegalArgumentException("Link URL is required");
        if (v.length() > 1000) throw new IllegalArgumentException("Link URL is too long");
        if (!v.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) v = "https://" + v;
        try {
            URI u = new URI(v);
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("https") && !scheme.equals("http")) throw new IllegalArgumentException("Links must start with http:// or https://");
            if (u.getHost() == null || !u.getHost().contains(".")) throw new IllegalArgumentException("That doesn't look like a web address");
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("That doesn't look like a web address");
        }
        return v;
    }

    static String labelOrDefault(String label, String url) {
        String l = label == null ? "" : label.strip().replaceAll("[\\r\\n]+", " ");
        if (l.length() > 100) throw new IllegalArgumentException("Label is too long (max 100 characters)");
        if (!l.isEmpty()) return l;
        String host = URI.create(url).getHost().toLowerCase(Locale.ROOT).replaceFirst("^(www|m)\\.", "");
        return KNOWN_HOSTS.getOrDefault(host, host);
    }
}
