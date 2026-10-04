package com.creatorcrm.web;

import com.creatorcrm.desktop.StartWithWindows;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Settings that only apply to the installed desktop app. */
@RestController
public class DesktopController {

    public record Toggle(boolean enabled) {}

    private final StartWithWindows startWithWindows;

    public DesktopController(StartWithWindows startWithWindows) {
        this.startWithWindows = startWithWindows;
    }

    @GetMapping("/api/desktop/start-with-windows")
    public StartWithWindows.Status startWithWindows() {
        return startWithWindows.status();
    }

    @PutMapping("/api/desktop/start-with-windows")
    public StartWithWindows.Status setStartWithWindows(@RequestBody Toggle body) {
        return startWithWindows.set(body.enabled());
    }
}
