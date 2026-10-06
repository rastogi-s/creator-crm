package com.creatorcrm.practice;

import com.creatorcrm.demo.DemoData;
import com.creatorcrm.security.UserAccounts;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The practice copy's side: sign straight in from the real app, show the practice banner, leave. */
@RestController
@ConditionalOnProperty(name = "crm.practice", havingValue = "true")
public class PracticeSession {
    private final UserAccounts accounts;
    private final String returnUrl;
    private final HttpSessionSecurityContextRepository contexts = new HttpSessionSecurityContextRepository();

    public PracticeSession(UserAccounts accounts, @Value("${crm.practice-return-url:/}") String returnUrl) {
        this.accounts = accounts;
        this.returnUrl = returnUrl;
    }

    /** The real app sends her here with the ticket it made, so she doesn't need the practice password. */
    @GetMapping("/practice/enter")
    public void enter(@RequestParam(required = false) String ticket, HttpServletRequest req, HttpServletResponse res)
            throws IOException {
        if (!PracticeTicket.matches(ticket)) {
            res.sendRedirect(returnUrl);
            return;
        }
        UserDetails user = accounts.loadUserByUsername(DemoData.USERNAME);
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities()));
        SecurityContextHolder.setContext(ctx);
        if (req.getSession(false) != null) req.changeSessionId();
        contexts.saveContext(ctx, req, res);
        res.sendRedirect("/#today");
    }

    @GetMapping("/api/practice")
    public Map<String, Object> status() {
        return Map.of("inside", true, "returnUrl", returnUrl);
    }

    @PostMapping("/api/practice/leave")
    public Map<String, Object> leave() {
        PracticeTicket.requestStop();
        return Map.of("returnUrl", returnUrl);
    }
}
