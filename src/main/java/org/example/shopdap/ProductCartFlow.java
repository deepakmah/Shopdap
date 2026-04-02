package org.example.shopdap;

import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.Keys;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.FluentWait;
import org.openqa.selenium.support.ui.Select;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * PDP swatches, Add to Cart, success modal, minicart and full cart; listing href collection.
 */
public final class ProductCartFlow {
    private ProductCartFlow() {}

/** Links inside a category / search product tile (Magento-style listing). */
private static final List<By> PRODUCT_TILE_LINKS = List.of(
        By.cssSelector("a.product-item-link"),
        By.cssSelector(".product-item-name a"),
        By.cssSelector("strong.product-item-name a"),
        By.cssSelector("a.product.photo"),
        By.cssSelector("a.product-item-photo"));

/** Magento PDP / buy box: try in order until one matches the theme. */
private static final List<By> ADD_TO_CART_SELECTORS = List.of(
        By.id("product-addtocart-button"),
        By.cssSelector("button#product-addtocart-button"),
        By.cssSelector("button.action.primary.tocart"),
        By.cssSelector("button.action.tocart"),
        By.cssSelector("form[data-role='tocart-form'] button[type='submit']"),
        By.cssSelector(".box-tocart button.tocart"));

/**
 * Ordered unique product URLs on the current category grid (same tile selectors as listing counts).
 */
private static List<String> collectOrderedProductListingHrefs(WebDriver driver) {
    List<By> tileSelectors = List.of(
            By.cssSelector("li.product-item"),
            By.cssSelector(".products.list.items .product-item"),
            By.cssSelector(".products-grid .product-item"),
            By.cssSelector(".product-items .product-item"),
            By.cssSelector("[data-container='product-grid'] .product-item"),
            By.cssSelector("ol.products li.item.product"));
    for (By tileBy : tileSelectors) {
        List<String> hrefs = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (WebElement tile : driver.findElements(tileBy)) {
            try {
                if (!tile.isDisplayed()) continue;
            } catch (Exception e) {
                continue;
            }
            WebElement pick = null;
            for (By linkBy : PRODUCT_TILE_LINKS) {
                List<WebElement> found;
                try {
                    found = tile.findElements(linkBy);
                } catch (Exception e) {
                    continue;
                }
                for (WebElement a : found) {
                    try {
                        if (a.isDisplayed()) {
                            pick = a;
                            break;
                        }
                    } catch (Exception e) {
                        // next
                    }
                }
                if (pick != null) break;
            }
            if (pick == null) continue;
            String href = "";
            try {
                href = pick.getDomAttribute("href");
                if (href == null || href.isEmpty()) href = pick.getAttribute("href");
            } catch (Exception ignored) { }
            if (href == null || href.isBlank()) continue;
            String norm = href.split("#", 2)[0];
            if (seen.add(norm)) hrefs.add(norm);
        }
        if (!hrefs.isEmpty()) return hrefs;
    }
    return Collections.emptyList();
}

/** Digital / email-delivery PDPs (e.g. “Availability: Via Email”) — no normal Add to Cart. */
private static boolean availabilityEmailOnlyIndicatesNoCart(WebDriver driver) {
    List<By> bys = List.of(
            By.xpath("//div[contains(@class,'product-info-main')]//*[contains(.,'Via Email')]"),
            By.xpath("//*[contains(@class,'availability')][contains(.,'Via Email')]"),
            By.xpath("//*[contains(@class,'product-info-stock-sku')][contains(.,'Via Email')]"));
    for (By by : bys) {
        for (WebElement e : driver.findElements(by)) {
            try {
                if (e.isDisplayed()) return true;
            } catch (Exception ignored) { }
        }
    }
    return false;
}

/** Polls until any {@link #ADD_TO_CART_SELECTORS} match is displayed and enabled, or timeout. */
private static WebElement findVisibleEnabledAddToCartButtonWithin(WebDriver driver, int timeoutSeconds) {
    long end = System.currentTimeMillis() + Math.max(1, timeoutSeconds) * 1000L;
    while (System.currentTimeMillis() < end) {
        for (By by : ADD_TO_CART_SELECTORS) {
            for (WebElement e : driver.findElements(by)) {
                try {
                    if (e.isDisplayed() && e.isEnabled()) return e;
                } catch (Exception ignored) { }
            }
        }
        try {
            Thread.sleep(150);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            break;
        }
    }
    return null;
}

/** Scrolls PDP buy box into view so lazy / below-fold Add to Cart can be detected. */
private static void scrollProductPageForAddToCartCheck(WebDriver driver) throws InterruptedException {
    JavascriptExecutor js = (JavascriptExecutor) driver;
    for (By box : List.of(
            By.cssSelector(".box-tocart"),
            By.cssSelector("form[data-role='tocart-form']"),
            By.cssSelector(".product-info-main"))) {
        for (WebElement el : driver.findElements(box)) {
            try {
                if (el.isDisplayed()) {
                    js.executeScript("arguments[0].scrollIntoView({block:'center'});", el);
                    Thread.sleep(ShopdapConfig.getInt("pdpBeforeCartButtonCheckMs", 250));
                    return;
                }
            } catch (Exception ignored) { }
        }
    }
}

/**
 * Magento configurable PDP: for each {@code .swatch-attribute} whose hidden {@code input.swatch-input} is still empty,
 * picks one visible, non-disabled {@code .swatch-option} at random and clicks it. Re-scans the DOM after each click
 * so multiple attributes (e.g. color then size) are filled. No-op if {@code pdpRandomSwatchEnabled=false} or no swatches.
 */
private static void selectRandomMagentoSwatchesIfPresent(WebDriver driver, JavascriptExecutor js)
        throws InterruptedException {
    if (!ShopdapConfig.get("pdpRandomSwatchEnabled", "true").equalsIgnoreCase("true")) {
        return;
    }
    for (WebElement sw : driver.findElements(By.cssSelector(".swatch-opt"))) {
        try {
            if (sw.isDisplayed()) {
                js.executeScript("arguments[0].scrollIntoView({block:'center'});", sw);
                Thread.sleep(ShopdapConfig.getInt("pdpBeforeSwatchScrollPauseMs", 200));
                break;
            }
        } catch (Exception ignored) { }
    }

    int maxPasses = ShopdapConfig.getInt("pdpSwatchMaxPasses", 8);
    int afterMs = ShopdapConfig.getInt("pdpAfterSwatchClickMs", 400);

    for (int pass = 0; pass < maxPasses; pass++) {
        List<WebElement> attrs = driver.findElements(By.cssSelector(".swatch-opt .swatch-attribute"));
        if (attrs.isEmpty()) {
            attrs = driver.findElements(By.cssSelector(".product-options-wrapper .swatch-attribute"));
        }
        boolean clickedThisPass = false;
        for (WebElement attr : attrs) {
            try {
                if (!attr.isDisplayed()) {
                    continue;
                }
            } catch (StaleElementReferenceException e) {
                continue;
            }

            WebElement hidden;
            try {
                hidden = attr.findElement(By.cssSelector("input.swatch-input"));
            } catch (Exception e) {
                continue;
            }
            String val = hidden.getDomAttribute("value");
            if (val == null || val.isEmpty()) {
                val = hidden.getAttribute("value");
            }
            if (val != null && !val.isBlank()) {
                continue;
            }

            List<WebElement> options = attr.findElements(By.cssSelector(".swatch-attribute-options .swatch-option"));
            List<WebElement> candidates = new ArrayList<>();
            for (WebElement opt : options) {
                try {
                    if (!opt.isDisplayed()) {
                        continue;
                    }
                    String cls = opt.getAttribute("class");
                    if (cls != null && (cls.contains("disabled") || cls.contains("unavailable"))) {
                        continue;
                    }
                    candidates.add(opt);
                } catch (Exception ignored) { }
            }
            if (candidates.isEmpty()) {
                continue;
            }

            WebElement pick = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
            String label = pick.getAttribute("data-option-label");
            if (label == null || label.isBlank()) {
                label = pick.getAttribute("aria-label");
            }
            js.executeScript("arguments[0].scrollIntoView({block:'center'});", pick);
            Thread.sleep(Math.min(300, Math.max(80, afterMs / 2)));
            try {
                pick.click();
            } catch (Exception e) {
                js.executeScript("arguments[0].click();", pick);
            }
            Thread.sleep(afterMs);
            System.out.println("PDP swatch: random selection — " + (label != null && !label.isBlank() ? label : pick.getText()));
            clickedThisPass = true;
            break;
        }
        if (!clickedThisPass) {
            return;
        }
    }
}

/**
 * True when this PDP cannot be added to cart: email-only / digital messaging, or no visible enabled
 * Add to Cart within {@code addToCartPresenceCheckSeconds} (after scrolling to the buy box).
 */
public static boolean productPageUnavailableForAddToCart(WebDriver driver) {
    try {
        scrollProductPageForAddToCartCheck(driver);
        selectRandomMagentoSwatchesIfPresent(driver, (JavascriptExecutor) driver);
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
    }
    if (availabilityEmailOnlyIndicatesNoCart(driver)) return true;
    int quickSec = ShopdapConfig.getInt("addToCartPresenceCheckSeconds", 8);
    return findVisibleEnabledAddToCartButtonWithin(driver, quickSec) == null;
}

/**
 * Clicks the first visible product link on the current listing (category / grid page).
 *
 * @return the href opened (best effort), or empty string
 */
public static String clickFirstVisibleProductFromListing(WebDriver driver) throws Exception {
    List<String> hrefs = collectOrderedProductListingHrefs(driver);
    if (hrefs.isEmpty()) {
        throw new NoSuchElementException(
                "No visible product link on listing (tried li.product-item + product-item-link / name link).");
    }
    String href = hrefs.get(0);
    driver.get(href);
    return href;
}

/**
 * On a product detail page, selects random Magento swatch options if required, then clicks the primary Add to Cart control.
 */
public static void clickAddToCartOnProductPage(WebDriver driver) throws Exception {
    JavascriptExecutor js = (JavascriptExecutor) driver;
    scrollProductPageForAddToCartCheck(driver);
    selectRandomMagentoSwatchesIfPresent(driver, js);

    int timeoutSec = ShopdapConfig.getInt("addToCartButtonTimeoutSeconds", 20);
    FluentWait<WebDriver> wait = WebDriverWaits.waitDriver(driver, timeoutSec);
    WebElement btn;
    try {
        btn = wait.until(d -> {
            for (By by : ADD_TO_CART_SELECTORS) {
                for (WebElement e : d.findElements(by)) {
                    try {
                        if (e.isDisplayed() && e.isEnabled()) return e;
                    } catch (Exception ignored) { }
                }
            }
            return null;
        });
    } catch (TimeoutException e) {
        throw new NoSuchElementException(
                "Add to Cart button not found or not enabled within " + timeoutSec + "s on PDP.");
    }
    js.executeScript("arguments[0].scrollIntoView({block:'center'});", btn);
    Thread.sleep(ShopdapConfig.getInt("pdpBeforeAddToCartMs", 250));
    try {
        btn.click();
    } catch (Exception e) {
        js.executeScript("arguments[0].click();", btn);
    }
}

/**
 * Dismisses the Magento-style “added to your cart successfully” popup: tries header
 * {@code button.action-close[data-role='closeBtn']}, then {@code #shopcontinue} / Continue Shopping.
 * Safe to call if no modal is shown (times out quietly).
 */
public static void closeAddToCartSuccessModal(WebDriver driver) throws Exception {
    JavascriptExecutor js = (JavascriptExecutor) driver;
    int timeoutSec = ShopdapConfig.getInt("addToCartModalTimeoutSeconds", 15);
    FluentWait<WebDriver> wait = WebDriverWaits.waitDriver(driver, timeoutSec);
    try {
        wait.until(d -> {
            for (WebElement c : d.findElements(By.cssSelector("#confirm_content"))) {
                try {
                    if (c.isDisplayed()) return true;
                } catch (Exception ignored) { }
            }
            for (WebElement b : d.findElements(
                    By.cssSelector(".modal-popup._show button.action-close[data-role='closeBtn']"))) {
                try {
                    if (b.isDisplayed()) return true;
                } catch (Exception ignored) { }
            }
            return false;
        });
    } catch (TimeoutException e) {
        System.out.println("closeAddToCartSuccessModal: no add-to-cart confirmation modal within " + timeoutSec + "s.");
        return;
    }

    Thread.sleep(ShopdapConfig.getInt("addToCartModalOpenWaitMs", 400));

    WebElement closeBtn = findVisibleAddToCartModalClose(driver);
    if (closeBtn != null) {
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", closeBtn);
        Thread.sleep(120);
        try {
            closeBtn.click();
        } catch (StaleElementReferenceException e) {
            WebElement again = findVisibleAddToCartModalClose(driver);
            if (again != null) js.executeScript("arguments[0].click();", again);
        } catch (Exception e) {
            js.executeScript("arguments[0].click();", closeBtn);
        }
    } else {
        WebElement cont = findVisibleContinueShoppingAfterCart(driver);
        if (cont != null) {
            js.executeScript("arguments[0].scrollIntoView({block:'center'});", cont);
            Thread.sleep(120);
            try {
                cont.click();
            } catch (Exception e) {
                js.executeScript("arguments[0].click();", cont);
            }
        } else {
            System.out.println("closeAddToCartSuccessModal: could not find Close or Continue Shopping.");
            return;
        }
    }

    try {
        WebDriverWaits.waitDriver(driver, 8).until(d -> {
            for (WebElement m : d.findElements(By.cssSelector(".modal-popup._show"))) {
                try {
                    if (m.isDisplayed()) return false;
                } catch (Exception ignored) { }
            }
            return true;
        });
    } catch (Exception ignored) { }
    Thread.sleep(ShopdapConfig.getInt("afterCloseCartModalMs", 200));
}

/** Magento add-to-cart success modal: header close button variants. */
private static WebElement findVisibleAddToCartModalClose(WebDriver driver) {
    List<By> closers = List.of(
            By.cssSelector(".modal-popup._show button.action-close[data-role='closeBtn']"),
            By.cssSelector(".modal-inner-wrap button.action-close[data-role='closeBtn']"),
            By.cssSelector(".modal-popup._show .modal-header button.action-close"),
            By.xpath("//div[contains(@class,'modal-inner-wrap')]//button[@data-role='closeBtn']"));
    for (By by : closers) {
        for (WebElement e : driver.findElements(by)) {
            try {
                if (e.isDisplayed()) return e;
            } catch (Exception ignored) { }
        }
    }
    return null;
}

/**
 * TestRigor-style paths for full cart: first visible qty control near label/column "Qty" (Magento cart table).
 */
private static WebElement findCartQtyInput(WebDriver driver) {
    List<By> qtyPaths = List.of(
            By.xpath("//label[normalize-space()='Qty']/following::input[1]"),
            By.xpath("//label[contains(normalize-space(),'Qty')]/following::input[1]"),
            By.xpath("//th[contains(normalize-space(),'Qty')]/ancestor::table//input[contains(@class,'qty') or contains(@name,'qty')]"),
            By.cssSelector("#shopping-cart-table input.qty"),
            By.cssSelector("#shopping-cart-table input.input-text.qty"),
            By.cssSelector(".cart.table-wrapper input.input-text.qty"),
            By.cssSelector("form#form-validate input.input-text.qty"),
            By.xpath("//table[contains(@class,'cart')]//input[contains(@name,'qty')]"));
    for (By by : qtyPaths) {
        for (WebElement el : driver.findElements(by)) {
            try {
                if (el.isDisplayed() && el.isEnabled()) {
                    return el;
                }
            } catch (Exception ignored) { }
        }
    }
    return null;
}

/**
 * TestRigor path "Update Shopping Cart" — Magento submit / button variants.
 */
private static WebElement findUpdateShoppingCartButton(WebDriver driver) {
    List<By> bys = List.of(
            By.xpath("//button[normalize-space()='Update Shopping Cart']"),
            By.xpath("//span[normalize-space()='Update Shopping Cart']/ancestor::button[1]"),
            By.xpath("//button[contains(normalize-space(),'Update Shopping Cart')]"),
            By.cssSelector("button[name='update_cart_action']"),
            By.cssSelector("button.action.update"),
            By.xpath("//input[@type='submit' and contains(@value,'Update Shopping Cart')]"));
    for (By by : bys) {
        for (WebElement e : driver.findElements(by)) {
            try {
                if (e.isDisplayed() && e.isEnabled()) {
                    return e;
                }
            } catch (Exception ignored) { }
        }
    }
    return null;
}

/**
 * Sets cart line qty (default from config), clicks Update Shopping Cart, waits, screenshots.
 */
private static void tryUpdateCartQtyAndScreenshot(WebDriver driver, JavascriptExecutor js) throws Exception {
    if (!ShopdapConfig.get("shoppingCartQtyUpdateEnabled", "true").equalsIgnoreCase("true")) {
        return;
    }
    String qtyStr = ShopdapConfig.get("shoppingCartQtyValue", "5");
    WebElement qtyIn = findCartQtyInput(driver);
    if (qtyIn == null) {
        System.out.println("Cart: no Qty input found (TestRigor-style paths); skipping qty update.");
        return;
    }
    js.executeScript("arguments[0].scrollIntoView({block:'center'});", qtyIn);
    Thread.sleep(ShopdapConfig.getInt("beforeCartQtyInteractMs", 200));
    try {
        qtyIn.click();
    } catch (Exception e) {
        js.executeScript("arguments[0].click();", qtyIn);
    }
    qtyIn.sendKeys(Keys.chord(Keys.CONTROL, "a"), Keys.BACK_SPACE);
    qtyIn.sendKeys(qtyStr);

    WebElement updateBtn = findUpdateShoppingCartButton(driver);
    if (updateBtn == null) {
        System.out.println("Cart: Update Shopping Cart button not found; qty typed but not submitted.");
        return;
    }
    js.executeScript("arguments[0].scrollIntoView({block:'center'});", updateBtn);
    Thread.sleep(ShopdapConfig.getInt("beforeUpdateShoppingCartClickMs", 250));
    try {
        updateBtn.click();
    } catch (Exception e) {
        js.executeScript("arguments[0].click();", updateBtn);
    }
    WebDriverWaits.waitForPageToLoad(driver);
    Thread.sleep(ShopdapConfig.getInt("afterUpdateShoppingCartWaitMs", 1000));
    ScreenshotReporter.takeScreenshotOrFullPage(driver, "shopping_cart_after_qty_update", true,
            "Qty=" + qtyStr + " | Update Shopping Cart | " + driver.getCurrentUrl());
}

/**
 * Magento cart: estimate form lives inside collapsible {@code #block-shipping}; content is often display:none until expanded.
 */
private static void expandMagentoEstimateShippingBlock(WebDriver driver, JavascriptExecutor js) throws InterruptedException {
    js.executeScript(
            "var el = document.querySelector('#block-shipping') || document.querySelector('.block.block-shipping');"
                    + "if (el) el.scrollIntoView({block:'center'});");
    Thread.sleep(ShopdapConfig.getInt("beforeEstimateShippingInteractMs", 300));
    WebElement heading = firstEstimateShippingHeading(driver);
    if (heading != null) {
        try {
            js.executeScript("arguments[0].click();", heading);
        } catch (Exception e) {
            try {
                heading.click();
            } catch (Exception ignored) {
                // script unhide below still runs
            }
        }
    } else {
        js.executeScript("var h=document.querySelector('#block-shipping-heading'); if(h) h.click();");
    }
    Thread.sleep(ShopdapConfig.getInt("afterEstimateShippingHeaderClickMs", 400));
    js.executeScript(
            "var block=document.querySelector('#block-shipping')||document.querySelector('.block.block-shipping');"
                    + "if(block){block.classList.remove('collapsed');"
                    + "var c=block.querySelector('[data-role=content]')||block.querySelector('.content');"
                    + "if(c){c.style.display='block';c.style.visibility='visible';c.style.height='auto';"
                    + "c.removeAttribute('hidden');}"
                    + "var t=block.querySelector('#block-shipping-heading,[data-role=title]');"
                    + "if(t){t.setAttribute('aria-expanded','true');}}"
                    + "var f=document.querySelector('form#shipping-zip-form');"
                    + "if(f){var n=f;for(var i=0;i<10&&n&&n!==document.body;i++){"
                    + "if(n.style){n.style.display='block';n.style.visibility='visible';}"
                    + "n.removeAttribute('hidden');n=n.parentElement;}}");
    Thread.sleep(ShopdapConfig.getInt("afterEstimateShippingUnhideMs", 350));
}

private static WebElement firstEstimateShippingHeading(WebDriver driver) {
    List<By> bys = List.of(
            By.id("block-shipping-heading"),
            By.cssSelector("#block-shipping [data-role='title']"),
            By.cssSelector(".block-shipping > .title"),
            By.cssSelector(".block-shipping .title"));
    for (By by : bys) {
        for (WebElement el : driver.findElements(by)) {
            try {
                if (el.isDisplayed()) {
                    return el;
                }
            } catch (Exception ignored) {
                // next
            }
        }
    }
    for (By by : bys) {
        List<WebElement> els = driver.findElements(by);
        if (!els.isEmpty()) {
            return els.get(0);
        }
    }
    return null;
}

/**
 * Opens the Estimate Shipping and Tax block ({@code form#shipping-zip-form}), fills country / region / postcode from config,
 * waits, then captures a full-page screenshot.
 */
private static void tryEstimateShippingTaxAndFullPageScreenshot(WebDriver driver, JavascriptExecutor js) throws Exception {
    if (!ShopdapConfig.get("estimateShippingTaxEnabled", "true").equalsIgnoreCase("true")) {
        return;
    }
    int domWait = ShopdapConfig.getInt("estimateShippingDomWaitSeconds", 15);
    int visWait = ShopdapConfig.getInt("estimateShippingVisibleWaitSeconds", 15);
    try {
        WebDriverWaits.waitDriver(driver, domWait).until(d ->
                !d.findElements(By.cssSelector("#block-shipping, form#shipping-zip-form")).isEmpty());
    } catch (TimeoutException e) {
        System.out.println("Cart: #block-shipping / form#shipping-zip-form not in DOM; skipping estimate shipping.");
        return;
    }
    expandMagentoEstimateShippingBlock(driver, js);
    WebElement form;
    try {
        form = WebDriverWaits.waitDriver(driver, visWait).until(d -> {
            for (WebElement f : d.findElements(By.cssSelector("form#shipping-zip-form"))) {
                try {
                    if (f.isDisplayed()) {
                        return f;
                    }
                } catch (Exception ignored) {
                    // next
                }
            }
            for (WebElement s : d.findElements(By.cssSelector("form#shipping-zip-form select[name='country_id']"))) {
                try {
                    if (s.isDisplayed()) {
                        return s.findElement(By.xpath("ancestor::form[1]"));
                    }
                } catch (Exception ignored) {
                    // next
                }
            }
            return null;
        });
    } catch (TimeoutException e) {
        System.out.println("Cart: form#shipping-zip-form not visible after expanding shipping block; skipping estimate shipping.");
        return;
    }
    js.executeScript("arguments[0].scrollIntoView({block:'center'});", form);
    Thread.sleep(ShopdapConfig.getInt("beforeEstimateShippingFillMs", 200));
    String countryVal = ShopdapConfig.get("estShipCountryId", "US");
    String regionVal = ShopdapConfig.get("estShipRegionId", "12");
    String postcodeVal = ShopdapConfig.get("estShipPostcode", "90210");
    try {
        form = driver.findElement(By.cssSelector("form#shipping-zip-form"));
        WebElement countrySel = form.findElement(By.cssSelector("select[name='country_id']"));
        new Select(countrySel).selectByValue(countryVal);
        Thread.sleep(ShopdapConfig.getInt("estShipAfterCountrySelectMs", 800));
        form = driver.findElement(By.cssSelector("form#shipping-zip-form"));
        WebElement regionSel = form.findElement(By.cssSelector("select[name='region_id']"));
        new Select(regionSel).selectByValue(regionVal);
        form = driver.findElement(By.cssSelector("form#shipping-zip-form"));
        WebElement zip = form.findElement(By.cssSelector("input[name='postcode']"));
        try {
            zip.click();
        } catch (Exception e) {
            js.executeScript("arguments[0].click();", zip);
        }
        zip.sendKeys(Keys.chord(Keys.CONTROL, "a"), Keys.BACK_SPACE);
        zip.sendKeys(postcodeVal);
        zip.sendKeys(Keys.TAB);
    } catch (NoSuchElementException e) {
        System.out.println("Cart: estimate shipping fields not found: " + e.getMessage());
        return;
    }
    Thread.sleep(ShopdapConfig.getInt("estShipAfterFillWaitMs", 1800));
    Thread.sleep(ShopdapConfig.getInt("beforeEstimateShippingFullPageScreenshotMs", 2000));
    ScreenshotReporter.takeScreenshotOrFullPage(driver, "cart_estimate_shipping_filled", true,
            "Estimate Shipping and Tax | country=" + countryVal + " region=" + regionVal + " zip=" + postcodeVal
                    + " | " + driver.getCurrentUrl());
}

/** Fallback when there is no X: site-specific Continue Shopping control. */
private static WebElement findVisibleContinueShoppingAfterCart(WebDriver driver) {
    List<By> bys = List.of(By.id("shopcontinue"), By.cssSelector("button.shopcontinue"),
            By.xpath("//button[contains(normalize-space(),'Continue Shopping')]"));
    for (By by : bys) {
        for (WebElement e : driver.findElements(by)) {
            try {
                if (e.isDisplayed()) return e;
            } catch (Exception ignored) { }
        }
    }
    return null;
}

/**
 * From the current listing: try each product URL (up to {@code addToCartMaxProductAttempts}) until one has
 * a working Add to Cart; then close the success modal, open minicart, optionally go to full cart, screenshots at each step.
 */
public static void openFirstListingProductAddToCartAndCloseModal(WebDriver driver) throws Exception {
    if (!ShopdapConfig.get("addToCartFlowEnabled", "true").equalsIgnoreCase("true")) {
        System.out.println("Add-to-cart flow skipped (addToCartFlowEnabled=false).");
        return;
    }
    if (FinderListingFlow.countVisibleProductItems(driver) < 1) {
        System.out.println("Add-to-cart flow: no visible product tiles; skipping.");
        return;
    }
    String listingUrl = driver.getCurrentUrl();
    List<String> productHrefs = collectOrderedProductListingHrefs(driver);
    if (productHrefs.isEmpty()) {
        System.out.println("Add-to-cart flow: no product URLs on listing; skipping.");
        return;
    }
    int maxAttempts = Math.min(
            ShopdapConfig.getInt("addToCartMaxProductAttempts", 12),
            productHrefs.size());
    String chosenPdp = null;
    for (int i = 0; i < maxAttempts; i++) {
        if (i > 0) {
            driver.get(listingUrl);
            WebDriverWaits.waitForPageToLoad(driver);
            Thread.sleep(ShopdapConfig.getInt("listingReturnSettleMs", 500));
        }
        driver.get(productHrefs.get(i));
        WebDriverWaits.waitForPageToLoad(driver);
        Thread.sleep(ShopdapConfig.getInt("pdpSettleMs", 400));
        if (productPageUnavailableForAddToCart(driver)) {
            System.out.println("Add-to-cart: no Add to Cart on this PDP — opening next listing product ("
                    + (i + 1) + "/" + maxAttempts + "): " + driver.getCurrentUrl());
            continue;
        }
        chosenPdp = driver.getCurrentUrl();
        try {
            clickAddToCartOnProductPage(driver);
            break;
        } catch (NoSuchElementException ex) {
            System.out.println("Add-to-cart: Add to Cart not clickable — trying next product ("
                    + (i + 1) + "/" + maxAttempts + "): " + driver.getCurrentUrl());
            chosenPdp = null;
        }
    }
    if (chosenPdp == null) {
        System.out.println("Add-to-cart flow: no product with a working Add to Cart in first " + maxAttempts
                + " listing tiles; skipping rest of flow.");
        return;
    }

    Thread.sleep(ShopdapConfig.getInt("afterAddToCartClickMs", 500));
    closeAddToCartSuccessModal(driver);
    WebDriverWaits.waitForPageToLoad(driver);
    Thread.sleep(ShopdapConfig.getInt("afterAddToCartModalScreenshotWaitMs", 700));

    String details = "Listing: " + listingUrl + " | PDP: " + chosenPdp;
    ScreenshotReporter.takeScreenshotOrFullPage(driver, "after_add_to_cart_modal_closed", true, details);

    JavascriptExecutor js = (JavascriptExecutor) driver;
    int miniWaitSec = ShopdapConfig.getInt("minicartOpenTimeoutSeconds", 8);
    FluentWait<WebDriver> miniWait = WebDriverWaits.waitDriver(driver, miniWaitSec);
    try {
        WebElement cartIcon = miniWait.until(d -> {
            for (WebElement e : d.findElements(By.cssSelector(".ti-shopping-cart.icon"))) {
                try {
                    if (e.isDisplayed()) return e;
                } catch (Exception ignored) { }
            }
            return null;
        });
        js.executeScript("arguments[0].scrollIntoView({block:'nearest'});", cartIcon);
        Thread.sleep(ShopdapConfig.getInt("beforeMinicartIconClickMs", 200));
        try {
            cartIcon.click();
        } catch (Exception e) {
            js.executeScript("arguments[0].click();", cartIcon);
        }
        Thread.sleep(ShopdapConfig.getInt("minicartDropdownOpenMs", 700));
        try {
            WebDriverWaits.waitDriver(driver, 6).until(d -> {
                for (WebElement el : d.findElements(By.cssSelector(
                        ".block-minicart .block-content, .minicart-items, #mini-cart, .minicart-wrapper"))) {
                    try {
                        if (el.isDisplayed()) return true;
                    } catch (Exception ignored) { }
                }
                return false;
            });
        } catch (TimeoutException ignored) {
            // still capture screenshot; panel may use different markup
        }
        Thread.sleep(ShopdapConfig.getInt("beforeMinicartScreenshotMs", 500));
        ScreenshotReporter.takeScreenshotOrFullPage(driver, "minicart_open", true,
                "Minicart open | " + driver.getCurrentUrl());
    } catch (TimeoutException e) {
        System.out.println("Minicart: .ti-shopping-cart.icon not visible within " + miniWaitSec + "s.");
        System.out.println("Add-to-cart flow completed. " + details);
        return;
    }

    try {
        WebElement viewCart = WebDriverWaits.waitDriver(driver, miniWaitSec).until(d -> {
            for (WebElement s : d.findElements(
                    By.xpath("//span[contains(@data-bind,'View and Edit Cart')]"))) {
                try {
                    if (s.isDisplayed()) return s;
                } catch (Exception ignored) { }
            }
            return null;
        });
        js.executeScript("arguments[0].scrollIntoView({block:'nearest'});", viewCart);
        Thread.sleep(ShopdapConfig.getInt("beforeViewCartClickMs", 200));
        try {
            viewCart.click();
        } catch (Exception e) {
            js.executeScript("arguments[0].click();", viewCart);
        }
        WebDriverWaits.waitForPageToLoad(driver);
        Thread.sleep(ShopdapConfig.getInt("shoppingCartPageSettleMs", 900));
        try {
            WebDriverWaits.waitDriver(driver, 12).until(d -> {
                for (WebElement el : d.findElements(By.cssSelector(
                        "#shopping-cart-table, .cart.table-wrapper, .cart-container, .checkout-cart-index"))) {
                    try {
                        if (el.isDisplayed()) return true;
                    } catch (Exception ignored) { }
                }
                return false;
            });
        } catch (TimeoutException ignored) {
            // cart page layout may differ; still screenshot after settle sleep
        }
        Thread.sleep(ShopdapConfig.getInt("beforeShoppingCartScreenshotMs", 400));
        details = details + " | Cart: " + driver.getCurrentUrl();
        ScreenshotReporter.takeScreenshotOrFullPage(driver, "shopping_cart_page", true, details);

        tryUpdateCartQtyAndScreenshot(driver, js);
        tryEstimateShippingTaxAndFullPageScreenshot(driver, js);
    } catch (TimeoutException e) {
        System.out.println("Minicart: span View and Edit Cart (data-bind) not found within " + miniWaitSec + "s.");
    }

    System.out.println("Add-to-cart flow completed. " + details);
}

}
