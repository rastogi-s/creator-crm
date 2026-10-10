package com.creatorcrm.demo;

import com.creatorcrm.news.NewsIndex;
import java.net.URI;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Demo mode only: no news index on the internet, so no articles. */
@Component
@Primary
@Profile("demo")
public class DemoNewsIndex extends NewsIndex {

    @Override
    protected String fetch(URI uri) {
        return "{\"articles\":[]}";
    }

    @Override
    protected void pause(long ms) {
        // nothing to wait for
    }
}
