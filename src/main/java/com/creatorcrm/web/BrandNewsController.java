package com.creatorcrm.web;

import com.creatorcrm.news.BrandNewsService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A past brand's recent launches and teasers: the list on the deal, "Check for news", and hiding an item. */
@RestController
@RequestMapping("/api")
public class BrandNewsController {

    private final BrandNewsService news;

    public BrandNewsController(BrandNewsService news) {
        this.news = news;
    }

    @GetMapping("/brands/{brandId}/news")
    public List<BrandNewsService.View> list(@PathVariable Long brandId) {
        return news.list(brandId);
    }

    /** Reads every source for this brand now. Website reads pause between pages, so this can take a minute. */
    @PostMapping("/brands/{brandId}/news/check")
    public BrandNewsService.CheckResult check(@PathVariable Long brandId) {
        return news.check(brandId);
    }

    @PostMapping("/news/{id}/hide")
    public BrandNewsService.View hide(@PathVariable Long id) {
        return news.setHidden(id, true);
    }

    @PostMapping("/news/{id}/show")
    public BrandNewsService.View show(@PathVariable Long id) {
        return news.setHidden(id, false);
    }
}
