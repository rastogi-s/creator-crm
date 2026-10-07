package com.creatorcrm.demo;

import com.creatorcrm.contacts.HunterApi;
import java.util.Map;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Demo mode only: made-up people at each brand's domain instead of asking hunter.io. Spends nothing. */
@Component
@Primary
@Profile("demo")
public class DemoHunter extends HunterApi {
    private double used = 0;

    @Override
    protected Reply call(String key, String path, Map<String, String> query) {
        return switch (path) {
            case "domain-search" -> {
                String d = query.get("domain");
                used += 1;
                yield new Reply(200, """
                        {"data":{"domain":"%1$s","organization":"Demo","accept_all":false,"pattern":"{first}","emails":[
                        {"value":"partnerships@%1$s","type":"generic","confidence":94,"department":"marketing",
                         "verification":{"date":"2026-09-20","status":"valid"},"sources":[{"uri":"https://%1$s/contact"}]},
                        {"value":"priya@%1$s","type":"personal","confidence":91,"first_name":"Priya","last_name":"Shah",
                         "position":"Influencer Marketing Manager","department":"marketing","linkedin":null,
                         "verification":{"date":"2026-09-02","status":"valid"},"sources":[{"uri":"https://%1$s/about"}]},
                        {"value":"marco@%1$s","type":"personal","confidence":82,"first_name":"Marco","last_name":"Diaz",
                         "position":"PR Lead","department":"communication","verification":null,
                         "sources":[{"uri":"https://%1$s/press"}]},
                        {"value":"sam.old@%1$s","type":"personal","confidence":40,"first_name":"Sam","last_name":"Lee",
                         "position":"Social Media Coordinator","department":"marketing",
                         "verification":{"date":"2026-08-10","status":"invalid"},"sources":[]}]},
                        "meta":{"results":4,"limit":10,"offset":0}}""".formatted(d));
            }
            case "email-verifier" -> {
                used += 0.5;
                String e = query.get("email");
                yield new Reply(200, e.startsWith("hello@") || e.startsWith("info@")
                        ? "{\"data\":{\"status\":\"accept_all\",\"result\":\"risky\",\"score\":60}}"
                        : "{\"data\":{\"status\":\"valid\",\"result\":\"deliverable\",\"score\":96}}");
            }
            case "account" -> new Reply(200, """
                    {"data":{"plan_name":"Free","reset_date":"%s","requests":{"credits":{"used":%s,"available":50}}}}"""
                    .formatted(java.time.LocalDate.now().withDayOfMonth(1).plusMonths(1), used));
            default -> new Reply(404, "{}");
        };
    }
}
