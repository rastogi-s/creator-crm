package com.creatorcrm.contacts;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

class WebsiteReaderTest {

    /** A website in memory. Records every request and every pause. */
    static class FakeSite extends WebsiteReader {
        final Map<String, Page> pages = new HashMap<>();
        final List<String> requested = new ArrayList<>();
        final List<Long> pauses = new ArrayList<>();
        boolean down;

        FakeSite() {
            super(null);
        }

        FakeSite page(String url, String html) {
            pages.put(url, new Page(200, "text/html; charset=utf-8", html, null));
            return this;
        }

        FakeSite robots(String url, String txt) {
            pages.put(url, new Page(200, "text/plain", txt, null));
            return this;
        }

        FakeSite answer(String url, int status, String location) {
            pages.put(url, new Page(status, "text/html", "", location));
            return this;
        }

        @Override
        protected Page fetch(URI uri) throws IOException {
            requested.add(uri.toString());
            if (down) throw new IOException("connection refused");
            return pages.getOrDefault(uri.toString(), new Page(404, "text/html", "", null));
        }

        @Override
        protected void pause(long ms) {
            pauses.add(ms);
        }
    }

    private static final String HOME = "<html><body><nav>"
            + "<a href='/collections/all'>Shop</a> <a href='/pages/work-with-us'>Work with us</a>"
            + " <a href='/pages/about'>About</a> <a href='https://shop.glowberry.com/pages/press'>Press</a>"
            + " <a href='https://www.instagram.com/glowberry'>Instagram</a> <a href='/account/login'>Log in</a>"
            + " <a href='https://agency.com/contact'>Made by Agency</a></nav>"
            + "<footer>Questions? hello@glowberry.com</footer></body></html>";

    @Test
    void readsHomepageAndContactPagesPolitely() {
        FakeSite site = new FakeSite()
                .robots("https://glowberry.com/robots.txt", "User-agent: *\nDisallow: /pages/about\n")
                .robots("https://shop.glowberry.com/robots.txt", "User-agent: *\nCrawl-delay: 10\n")
                .page("https://glowberry.com/", HOME)
                .page("https://glowberry.com/pages/work-with-us",
                        "<p>Creators: <a href='mailto:collabs@glowberry.com?subject=Hi'>Maya Lee</a></p>")
                .page("https://shop.glowberry.com/pages/press", "<script type='application/ld+json'>"
                        + "{\"@type\":\"Organization\",\"contactPoint\":[{\"contactType\":\"Press\",\"email\":\"PRESS@glowberry.com\"}]}"
                        + "</script>")
                .page("https://glowberry.com/contact", "<p>pr [at] glowberry [dot] com, or our agency team@agency.com</p>");

        WebsiteReader.Result r = site.read("glowberry.com/shop?ref=x");

        assertThat(r.problem()).isNull();
        assertThat(r.hits()).extracting(WebsiteReader.Hit::email)
                .containsExactly("hello@glowberry.com", "collabs@glowberry.com", "press@glowberry.com", "pr@glowberry.com");
        WebsiteReader.Hit collabs = r.hits().get(1);
        assertThat(collabs.name()).isEqualTo("Maya Lee");
        assertThat(collabs.pageUrl()).isEqualTo("https://glowberry.com/pages/work-with-us");
        assertThat(r.hits().get(2).title()).isEqualTo("Press");
        // robots.txt first, then pages; the disallowed About page, social links, the login page and the agency's
        // site are never requested.
        assertThat(site.requested.get(0)).isEqualTo("https://glowberry.com/robots.txt");
        assertThat(site.requested).noneMatch(u -> u.contains("about") || u.contains("instagram") || u.contains("login")
                || u.contains("agency.com") || u.contains("collections"));
        // A pause before every request but the first; the subdomain's Crawl-delay makes later pauses longer.
        assertThat(site.pauses).hasSize(site.requested.size() - 1);
        assertThat(site.pauses).allMatch(ms -> ms >= WebsiteReader.DELAY_MS);
        assertThat(site.pauses.get(site.pauses.size() - 1)).isEqualTo(10_000L);
        assertThat(r.pagesRead()).hasSizeLessThanOrEqualTo(WebsiteReader.MAX_PAGES);
    }

    @Test
    void readsNothingWhenRobotsSaysNo() {
        FakeSite site = new FakeSite()
                .robots("https://glowberry.com/robots.txt", "User-agent: CreatorCRM\nDisallow: /\n")
                .page("https://glowberry.com/", HOME);
        WebsiteReader.Result r = site.read("glowberry.com");
        assertThat(r.hits()).isEmpty();
        assertThat(r.problem()).contains("asks apps not to read");
        assertThat(site.requested).containsExactly("https://glowberry.com/robots.txt");
    }

    @Test
    void readsNothingWhenRobotsCannotBeRead() {
        FakeSite site = new FakeSite().answer("https://glowberry.com/robots.txt", 503, null).page("https://glowberry.com/", HOME);
        assertThat(site.read("glowberry.com").hits()).isEmpty();
        assertThat(site.requested).containsExactly("https://glowberry.com/robots.txt");
    }

    @Test
    void missingRobotsMeansAllowedAndRedirectsStayOnTheBrandsDomain() {
        FakeSite site = new FakeSite()
                .answer("https://glowberry.com/", 301, "https://www.glowberry.com/")
                .page("https://www.glowberry.com/", "<a href='/contact-us'>Contact</a>")
                .answer("https://www.glowberry.com/contact-us", 302, "https://evil.example/contact")
                .page("https://evil.example/contact", "<p>steal@evil.example</p>");
        WebsiteReader.Result r = site.read("https://glowberry.com");
        assertThat(r.pagesRead()).containsExactly("https://www.glowberry.com/");
        assertThat(site.requested).noneMatch(u -> u.contains("evil.example"));
    }

    @Test
    void neverReadsSocialProfilesOrSignInPages() {
        FakeSite site = new FakeSite();
        assertThat(site.read("https://instagram.com/glowberry").problem()).contains("social media");
        assertThat(site.read("linktr.ee/glowberry").problem()).contains("social media");
        assertThat(site.requested).isEmpty();

        FakeSite locked = new FakeSite().page("https://glowberry.com/", "<form><input type='password'></form>");
        assertThat(locked.read("glowberry.com").pagesRead()).isEmpty();
        FakeSite forbidden = new FakeSite().answer("https://glowberry.com/", 403, null);
        assertThat(forbidden.read("glowberry.com").problem()).contains("doesn't let apps read");
    }

    @Test
    void unreachableSiteGivesAPlainProblem() {
        FakeSite site = new FakeSite();
        site.down = true;
        WebsiteReader.Result r = site.read("glowberry.com");
        assertThat(r.hits()).isEmpty();
        assertThat(r.problem()).isNotBlank();
    }

    @Test
    void extractKeepsTheBrandsOwnAndPersonalAddressesOnly() {
        Document doc = Jsoup.parse("<p>Owner: jane.doe@gmail.com. Site by studio@webagency.io. Logo logo@2x.png."
                + " noreply@glowberry.com <a href='/cdn-cgi/l/email-protection' class='__cf_email__' data-cfemail='"
                + cf("partners@glowberry.co.uk", 0x5a) + "'>[email protected]</a></p>", "https://glowberry.co.uk/");
        assertThat(WebsiteReader.extract(doc, "https://glowberry.co.uk/", "glowberry.co.uk"))
                .extracting(WebsiteReader.Hit::email)
                .containsExactlyInAnyOrder("jane.doe@gmail.com", "partners@glowberry.co.uk");
    }

    @Test
    void contactLinksComeBeforeAboutLinks() {
        Document doc = Jsoup.parse("<a href='/pages/our-story'>Our story</a><a href='/pages/press'>Press</a>"
                + "<a href='/pages/contact'>Get in touch</a><a href='/products/creator-kit'>Creator kit</a>", "https://b.com/");
        assertThat(WebsiteReader.links(doc, URI.create("https://b.com/"), "b.com")).extracting(URI::getPath)
                .containsExactly("/pages/contact", "/pages/press", "/pages/our-story");
    }

    @Test
    void homeOfAndCloudflare() {
        assertThat(WebsiteReader.homeOf("Glowberry.com/shop?x=1")).hasToString("https://glowberry.com/");
        assertThat(WebsiteReader.homeOf("ftp://glowberry.com")).isNull();
        assertThat(WebsiteReader.homeOf("")).isNull();
        assertThat(WebsiteReader.cloudflare(cf("a@b.com", 0x11))).isEqualTo("a@b.com");
        assertThat(WebsiteReader.cloudflare("zz")).isNull();
    }

    @Test
    void refusesAddressesOnThisComputerOrHomeNetwork() {
        for (String host : List.of("localhost", "127.0.0.1", "192.168.1.10", "10.0.0.5", "169.254.169.254")) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> WebsiteReader.requirePublic(host)).isInstanceOf(IOException.class);
        }
    }

    @Test
    void identifiesItself() {
        assertThat(new FakeSite().userAgent()).contains("CreatorCRM/").contains("github.com/rastogi-s/creator-crm");
    }

    private static String cf(String email, int key) {
        StringBuilder sb = new StringBuilder(String.format("%02x", key));
        for (char c : email.toCharArray()) sb.append(String.format("%02x", c ^ key));
        return sb.toString();
    }
}
