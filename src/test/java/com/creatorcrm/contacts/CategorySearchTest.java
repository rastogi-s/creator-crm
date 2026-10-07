package com.creatorcrm.contacts;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class CategorySearchTest {

    @Test
    void readsTopicsAndBrandsFromWikidataAnswers() throws Exception {
        assertThat(CategorySearch.topics("{\"search\":[{\"id\":\"Q131221\",\"label\":\"skin care\"},{\"id\":\"P31\",\"label\":\"x\"}]}"))
                .containsExactly(java.util.Map.entry("Q131221", "skin care"));

        String rows = "{\"results\":{\"bindings\":["
                + "{\"b\":{\"value\":\"e/Q1\"},\"bLabel\":{\"value\":\"Glow Co\"},\"site\":{\"value\":\"https://glow.example\"},\"catLabel\":{\"value\":\"skin care\"}},"
                + "{\"b\":{\"value\":\"e/Q1\"},\"bLabel\":{\"value\":\"Glow Co\"},\"site\":{\"value\":\"https://glow.example/en\"},\"ig\":{\"value\":\"glowco\"}},"
                + "{\"b\":{\"value\":\"e/Q2\"},\"bLabel\":{\"value\":\"Q2\"},\"site\":{\"value\":\"https://q2.example\"}},"
                + "{\"b\":{\"value\":\"e/Q3\"},\"bLabel\":{\"value\":\"No Site\"},\"site\":{\"value\":\"ftp://x\"}}]}}";
        List<CategorySearch.Found> found = CategorySearch.brandsIn(rows);
        assertThat(found).singleElement().satisfies(f -> {
            assertThat(f.name()).isEqualTo("Glow Co");
            assertThat(f.website()).isEqualTo("https://glow.example");
            assertThat(f.instagram()).isEqualTo("glowco");
            assertThat(f.category()).isEqualTo("skin care");
        });
    }

    @Test
    void queryAsksForLiveOrganizationsWithAWebsite() {
        String q = CategorySearch.sparql(new LinkedHashSet<>(List.of("Q1", "Q2")));
        assertThat(q).contains("VALUES ?cat { wd:Q1 wd:Q2 }").contains("wdt:P856").contains("FILTER NOT EXISTS { ?b wdt:P576 [] }");
    }
}
