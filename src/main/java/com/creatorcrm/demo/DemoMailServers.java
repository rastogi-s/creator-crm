package com.creatorcrm.demo;

import com.creatorcrm.contacts.MailServers;
import java.util.List;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Demo mode only: every made-up brand domain has a mail server, without asking real DNS. */
@Component
@Primary
@Profile("demo")
public class DemoMailServers extends MailServers {
    @Override
    protected List<String> records(String domain, String type) {
        return "MX".equals(type) ? List.of("10 mx." + domain + ".") : List.of();
    }
}
