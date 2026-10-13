package com.creatorcrm.shop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.creatorcrm.domain.Product;
import org.junit.jupiter.api.Test;

class ProductLinksTest {

    @Test
    void amazonProductLinkIsTidiedAndGetsHerTag() {
        ProductLinks.Parsed p = ProductLinks.parse(
                "https://www.amazon.com/CeraVe-Hydrating-Facial-Cleanser/dp/B01MSSDEPK/ref=sr_1_3?crid=2X&keywords=cerave&sr=8-3", "ava-20", null);
        assertThat(p.store()).isEqualTo(Product.Store.AMAZON);
        assertThat(p.url()).isEqualTo("https://www.amazon.com/dp/B01MSSDEPK?tag=ava-20");
        assertThat(p.itemId()).isEqualTo("B01MSSDEPK");
        assertThat(p.name()).isEqualTo("CeraVe Hydrating Facial Cleanser");
        assertThat(p.warning()).isNull();
    }

    @Test
    void aTagAlreadyInTheLinkIsKept() {
        ProductLinks.Parsed p = ProductLinks.parse("amazon.co.uk/gp/product/B0CXYZ1234?tag=someone-21&linkCode=ll1", "ava-20", null);
        assertThat(p.url()).isEqualTo("https://www.amazon.co.uk/dp/B0CXYZ1234?tag=someone-21");
    }

    @Test
    void amazonLinkWithoutAnyTagWarns() {
        ProductLinks.Parsed p = ProductLinks.parse("https://www.amazon.com/dp/B01MSSDEPK", "", null);
        assertThat(p.url()).isEqualTo("https://www.amazon.com/dp/B01MSSDEPK");
        assertThat(p.warning()).contains("no Associates tag");
    }

    @Test
    void shortLinksKeepTheirAddressAndLearnTheNameFromWhereTheyLead() {
        ProductLinks.Parsed p = ProductLinks.parse("amzn.to/3xYzAbC", "ava-20",
                "https://www.amazon.com/Silk-Pillowcase-Queen/dp/B07ABCDEFG?tag=ava-20");
        assertThat(p.store()).isEqualTo(Product.Store.AMAZON);
        assertThat(p.url()).isEqualTo("https://amzn.to/3xYzAbC");
        assertThat(p.name()).isEqualTo("Silk Pillowcase Queen");
        assertThat(p.itemId()).isEqualTo("B07ABCDEFG");
        assertThat(p.shortLink()).isTrue();
        assertThat(p.warning()).isNull();
    }

    @Test
    void storefrontNeedsNoTag() {
        ProductLinks.Parsed p = ProductLinks.parse("https://www.amazon.com/shop/avamakes", "", null);
        assertThat(p.url()).isEqualTo("https://www.amazon.com/shop/avamakes");
        assertThat(p.name()).isEqualTo("Amazon storefront: avamakes");
        assertThat(p.warning()).isNull();
    }

    @Test
    void walmartAffiliateAndPlainLinks() {
        ProductLinks.Parsed plain = ProductLinks.parse("https://www.walmart.com/ip/Mainstays-Knit-Throw/123456789", "", null);
        assertThat(plain.store()).isEqualTo(Product.Store.WALMART);
        assertThat(plain.itemId()).isEqualTo("123456789");
        assertThat(plain.name()).isEqualTo("Mainstays Knit Throw");
        assertThat(plain.warning()).contains("plain Walmart link");

        ProductLinks.Parsed go = ProductLinks.parse("https://goto.walmart.com/c/123/456/9383?u=https%3A%2F%2Fwww.walmart.com%2Fip%2FCozy-Lamp%2F987654321", "", null);
        assertThat(go.itemId()).isEqualTo("987654321");
        assertThat(go.name()).isEqualTo("Cozy Lamp");
        assertThat(go.warning()).isNull();

        assertThat(ProductLinks.parse("walmrt.us/3abc", "", null).store()).isEqualTo(Product.Store.WALMART);
    }

    @Test
    void otherShopsGetANameFromTheAddress() {
        ProductLinks.Parsed p = ProductLinks.parse("https://shop.glowhaus.com/products/dew-drop-serum?variant=1", "ava-20", null);
        assertThat(p.store()).isEqualTo(Product.Store.OTHER);
        assertThat(p.name()).isEqualTo("dew drop serum");
        assertThat(p.url()).isEqualTo("https://shop.glowhaus.com/products/dew-drop-serum?variant=1");
    }

    @Test
    void onlyWebLinks() {
        assertThatThrownBy(() -> ProductLinks.parse("javascript:alert(1)", "", null)).hasMessageContaining("web links");
        assertThatThrownBy(() -> ProductLinks.parse("  ", "", null)).hasMessageContaining("Paste");
        assertThat(ProductLinks.isShortHost("amzn.to")).isTrue();
        assertThat(ProductLinks.isStoreHost("evil.example")).isFalse();
    }
}
