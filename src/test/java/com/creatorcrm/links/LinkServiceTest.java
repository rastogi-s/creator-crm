package com.creatorcrm.links;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.creatorcrm.domain.CreatorLink;
import com.creatorcrm.repo.CreatorLinkRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class LinkServiceTest {

    @Autowired LinkService links;
    @Autowired CreatorLinkRepo repo;

    @BeforeEach
    void clean() {
        repo.deleteAll();
    }

    @Test
    void addsLinksOnTheFlyWithFriendlyLabels() {
        CreatorLink ig = links.add("", "www.instagram.com/maya");
        assertThat(ig.url).isEqualTo("https://www.instagram.com/maya");
        assertThat(ig.label).isEqualTo("Instagram");
        assertThat(links.add(null, "https://youtu.be/abc").label).isEqualTo("YouTube");
        assertThat(links.add("Portfolio", "https://maya.design/work").label).isEqualTo("Portfolio");
        assertThat(links.add(" ", "https://maya.design").label).isEqualTo("maya.design");
        assertThat(links.list()).extracting(l -> l.label).containsExactly("Instagram", "YouTube", "Portfolio", "maya.design");
    }

    @Test
    void rejectsAnythingThatIsNotAWebAddress() {
        assertThatThrownBy(() -> links.add("x", "javascript:alert(1)")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> links.add("x", "ftp://files.example.com")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> links.add("x", "not a link")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> links.add("x", "")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void editsReordersAndDeletes() {
        CreatorLink a = links.add("A", "https://a.example");
        CreatorLink b = links.add("B", "https://b.example");
        links.move(b.id, true);
        assertThat(links.list()).extracting(l -> l.label).containsExactly("B", "A");
        links.update(a.id, "Website", "a.example/home");
        assertThat(repo.findById(a.id).orElseThrow().url).isEqualTo("https://a.example/home");
        links.delete(b.id);
        assertThat(links.list()).extracting(l -> l.label).containsExactly("Website");
    }

    @Test
    void draftWriterGetsTheLinksAsAProfileSection() {
        assertThat(links.profileSection()).isEmpty();
        links.add("TikTok", "tiktok.com/@maya");
        assertThat(links.profileSection()).contains("# My links").contains("- TikTok: https://tiktok.com/@maya");
    }
}
