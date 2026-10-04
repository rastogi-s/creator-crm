package com.creatorcrm.demo;

import com.creatorcrm.links.LinktreeImporter;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Demo mode only: a made-up Linktree page instead of fetching linktr.ee. */
@Component
@Primary
@Profile("demo")
public class DemoLinktree extends LinktreeImporter {

    @Override
    protected String download(String user) {
        return "<script id=\"__NEXT_DATA__\" type=\"application/json\">{\"props\":{\"pageProps\":{"
                + "\"account\":{\"pageTitle\":\"Maya Makes\"},\"links\":["
                + "{\"title\":\"UGC portfolio\",\"url\":\"https://mayamakes.my.canva.site/portfolio\"},"
                + "{\"title\":\"My Amazon storefront\",\"url\":\"https://www.amazon.com/shop/mayamakes\"},"
                + "{\"title\":\"Media kit\",\"url\":\"https://mayamakes.com/media-kit?utm_source=linktree\"},"
                + "{\"title\":\"20% off at Glow Botanics\",\"url\":\"https://glowbotanics.example/MAYA20\"}],"
                + "\"socialLinks\":[{\"type\":\"INSTAGRAM\",\"url\":\"https://www.instagram.com/mayamakes\"},"
                + "{\"type\":\"TIKTOK\",\"url\":\"https://www.tiktok.com/@mayamakes\"},"
                + "{\"type\":\"YOUTUBE\",\"url\":\"https://www.youtube.com/@mayamakes\"}]}}}</script>";
    }
}
