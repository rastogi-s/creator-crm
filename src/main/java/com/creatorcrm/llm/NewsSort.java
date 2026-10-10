package com.creatorcrm.llm;

import com.creatorcrm.domain.BrandNews;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.List;

/** Claude's verdict on each item of a {@link NewsSortInput}. */
public record NewsSort(
        @JsonPropertyDescription("One verdict per item, with the item's id.")
        List<Verdict> items) {

    public record Verdict(
            @JsonPropertyDescription("The item's id, as given.")
            long id,
            @JsonPropertyDescription("True only if the item announces or teases something new from the brand itself: a product "
                    + "launch, an upcoming drop, a restock of something that sold out, a new range or flavour, a new campaign, "
                    + "store or collaboration. False for sales and discount codes, generic newsletters, shipping notices, "
                    + "job posts, or an article that only mentions the brand in passing.")
            boolean isNews,
            @JsonPropertyDescription("What kind of news it is. OTHER when it isn't news.")
            BrandNews.Kind kind,
            @JsonPropertyDescription("One plain line a creator would say, under 110 characters, naming the product and any "
                    + "date the item states, e.g. 'Launching Barrier Mist, a travel face mist, on 1 November'. Only facts "
                    + "stated in the item. Empty when it isn't news.")
            String headline,
            @JsonPropertyDescription("One concrete content idea the creator could pitch around this news, in her usual formats, "
                    + "under 160 characters. Empty when it isn't news.")
            String pitchIdea,
            @JsonPropertyDescription("0-100: how well this news fits the creator's niche and audience as a reason to pitch "
                    + "the brand again now. 0 when it isn't news.")
            int fit) {}
}
