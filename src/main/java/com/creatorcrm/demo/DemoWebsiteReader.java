package com.creatorcrm.demo;

import com.creatorcrm.contacts.WebsiteReader;
import java.net.URI;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Demo mode only: a made-up brand website instead of the internet, with short pauses so videos stay short. */
@Component
@Primary
@Profile("demo")
public class DemoWebsiteReader extends WebsiteReader {

    private static final String HOME = "<html><head><script src=\"https://cdn.shopify.com/s/trailmix.js\"></script></head><body>"
            + "<nav><a href=\"/collections/all\">Shop</a> <a href=\"/pages/our-story\">Our story</a>"
            + " <a href=\"/pages/work-with-us\">Work with us</a> <a href=\"/pages/press\">Press</a></nav>"
            + "<h1>Trail snacks for long days outside</h1></body></html>";
    private static final Map<String, String> PAGES = Map.of(
            "/", HOME,
            "/robots.txt", "User-agent: *\nDisallow: /cart\nDisallow: /account\n",
            "/pages/work-with-us", "<html><body><h1>Work with us</h1><p>Creators and UGC makers: tell us about your audience."
                    + " Write to <a href=\"mailto:creators@trailmix.example\">Priya Shah</a>, our creator partnerships lead.</p></body></html>",
            "/pages/press", "<html><head><script type=\"application/ld+json\">{\"@type\":\"Organization\",\"contactPoint\":"
                    + "{\"@type\":\"ContactPoint\",\"contactType\":\"press\",\"email\":\"press@trailmix.example\"}}</script></head>"
                    + "<body><h1>Press</h1></body></html>",
            "/pages/our-story", "<html><body><h1>Our story</h1><p>Started on a trail in 2019.</p></body></html>",
            "/pages/contact", "<html><body><h1>Contact</h1><p>Questions about an order? hello [at] trailmix [dot] example</p></body></html>");

    public DemoWebsiteReader(ObjectProvider<BuildProperties> build) {
        super(build);
    }

    @Override
    protected Page fetch(URI uri) {
        String body = uri.getHost() != null && uri.getHost().endsWith("trailmix.example") ? PAGES.get(uri.getPath()) : null;
        if (body == null) return new Page(404, "text/html", "", null);
        return new Page(200, uri.getPath().endsWith(".txt") ? "text/plain" : "text/html; charset=utf-8", body, null);
    }

    @Override
    protected void pause(long ms) {
        super.pause(400);
    }
}
