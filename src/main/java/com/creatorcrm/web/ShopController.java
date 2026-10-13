package com.creatorcrm.web;

import com.creatorcrm.shop.CaptionService;
import com.creatorcrm.shop.ProductService;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Her shop products (affiliate links) and the post captions written with them. */
@RestController
@RequestMapping("/api")
public class ShopController {

    public record LinkBody(String url) {}

    public record Bulk(List<String> urls) {}

    private final ProductService products;
    private final CaptionService captions;

    public ShopController(ProductService products, CaptionService captions) {
        this.products = products;
        this.captions = captions;
    }

    @GetMapping("/products")
    public List<ProductService.View> products() {
        return products.list();
    }

    /** What a pasted link is (store, name, missing tag), before she saves it. */
    @PostMapping("/products/preview")
    public ProductService.Preview preview(@RequestBody LinkBody body) {
        return products.preview(body.url());
    }

    @PostMapping("/products")
    public ProductService.View add(@RequestBody ProductService.Request body) {
        return products.add(body);
    }

    @PostMapping("/products/bulk")
    public ProductService.Added bulk(@RequestBody Bulk body) {
        if (body.urls() == null || body.urls().size() > 100) throw new IllegalArgumentException("Paste up to 100 links at a time");
        return products.addAll(body.urls());
    }

    @PutMapping("/products/{id}")
    public ProductService.View update(@PathVariable Long id, @RequestBody ProductService.Request body) {
        return products.update(id, body);
    }

    @DeleteMapping("/products/{id}")
    public void delete(@PathVariable Long id) {
        products.delete(id);
    }

    @GetMapping("/captions")
    public List<CaptionService.Summary> recentCaptions() {
        return captions.recent();
    }

    @PostMapping("/captions")
    public CaptionService.View write(@RequestBody CaptionService.Request body) {
        return captions.write(body);
    }

    @GetMapping("/captions/{id}")
    public ResponseEntity<CaptionService.View> caption(@PathVariable Long id) {
        return captions.get(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/captions/{id}")
    public void deleteCaption(@PathVariable Long id) {
        captions.delete(id);
    }
}
