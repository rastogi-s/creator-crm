package com.creatorcrm.learning;

import static org.assertj.core.api.Assertions.assertThat;

import com.creatorcrm.domain.CreatorLink;
import com.creatorcrm.domain.Enums.Compensation;
import com.creatorcrm.domain.Enums.OpportunityStatus;
import com.creatorcrm.domain.Enums.Origin;
import com.creatorcrm.domain.Opportunity;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProfileDrafterTest {

    private static CreatorLink link(String url) {
        CreatorLink l = new CreatorLink();
        l.label = "x";
        l.url = url;
        return l;
    }

    @Test
    void claudeMayOpenHerOwnSitesButNotSocialNetworks() {
        List<String> domains = ProfileDrafter.fetchDomains(List.of(
                link("https://www.instagram.com/ava"), link("https://linktr.ee/ava"), link("https://www.AvaRivera.com/ugc"),
                link("https://ava.notion.site/portfolio"), link("https://avarivera.com/media-kit"), link("not a url")));
        assertThat(domains).containsExactly("avarivera.com", "ava.notion.site");
    }

    @Test
    void aDealIsOneLineWithWhatWasAgreed() {
        Opportunity o = new Opportunity();
        o.origin = Origin.INBOUND;
        o.compensation = Compensation.PAID;
        o.budgetAmount = new BigDecimal("800");
        o.currency = "USD";
        o.deliverables = "1 Reel\n+ 3 Stories";
        o.status = OpportunityStatus.POSTED;
        assertThat(ProfileDrafter.deal(o, "Glow <Co>"))
                .isEqualTo("Glow <Co> (brand reached out); paid; agreed or offered USD 800; deliverables: 1 Reel + 3 Stories; status: Posted");
    }
}
