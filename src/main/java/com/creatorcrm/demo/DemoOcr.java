package com.creatorcrm.demo;

import com.creatorcrm.contacts.ImageOcr;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Demo mode only: every picture "reads" as the same made-up business card, so the import can be filmed anywhere. */
@Component
@Primary
@Profile("demo")
public class DemoOcr implements ImageOcr {

    static final String CARD = String.join("\n",
            "SUNLEAF BOTANICS",
            "Maya Chen",
            "Influencer Partnerships Manager",
            "maya.chen@sunleafbotanics.com",
            "+1 (415) 555-0142",
            "sunleafbotanics.com",
            "@sunleafbotanics");

    @Override
    public String unavailable() {
        return null;
    }

    @Override
    public String read(byte[] image, String ext) {
        return CARD;
    }
}
