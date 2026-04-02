package org.example.shopdap;

import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.FluentWait;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/** Random YMM category navigation and product-finder listing verification. */
public final class FinderListingFlow {
    private FinderListingFlow() {}

public static void clickRandomFinderCategory(WebDriver driver) throws Exception {
    if (!ShopdapConfig.get("randomCategoryEnabled", "true").equalsIgnoreCase("true")) {
        System.out.println("Random category: skipped (randomCategoryEnabled=false).");
        return;
    }
    JavascriptExecutor js = (JavascriptExecutor) driver;
    int timeoutSec = ShopdapConfig.getInt("randomCategoryTimeoutSeconds", 12);
    FluentWait<WebDriver> wait = WebDriverWaits.waitDriver(driver, timeoutSec);
    try {
        wait.until(d -> {
            for (WebElement a : d.findElements(By.cssSelector("li.items a[href*='finder-data']"))) {
                try {
                    if (a.isDisplayed()) return true;
                } catch (Exception ignored) { }
            }
            return false;
        });
    } catch (TimeoutException e) {
        System.out.println("Random category: no finder-data category links within " + timeoutSec + "s; skipping.");
        return;
    }

    Set<String> seenHref = new HashSet<>();
    List<WebElement> candidates = new ArrayList<>();
    for (WebElement a : driver.findElements(By.cssSelector("li.items a[href*='finder-data']"))) {
        try {
            if (!a.isDisplayed()) continue;
            String href = a.getAttribute("href");
            if (href == null || href.isBlank()) continue;
            String norm = href.split("#", 2)[0];
            if (seenHref.add(norm)) candidates.add(a);
        } catch (Exception ignored) { }
    }
    if (candidates.isEmpty()) {
        System.out.println("Random category: no visible category links after wait; skipping.");
        return;
    }

    WebElement link = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
    String label = "";
    try {
        label = link.getAttribute("title");
        if (label == null || label.isBlank()) {
            label = link.getText() != null ? link.getText().trim() : "";
        }
    } catch (Exception ignored) { }

    js.executeScript("arguments[0].scrollIntoView({block:'center'});", link);
    Thread.sleep(180);
    String beforeUrl = driver.getCurrentUrl();
    try {
        link.click();
    } catch (Exception e) {
        js.executeScript("arguments[0].click();", link);
    }
    Thread.sleep(ShopdapConfig.getInt("randomCategoryAfterClickMs", 500));
    WebDriverWaits.waitForPageToLoad(driver);

    String afterUrl = driver.getCurrentUrl();
    String details = String.format(
            "Random category%s | before: %s | after: %s",
            label.isEmpty() ? "" : " \"" + label + "\"",
            beforeUrl,
            afterUrl);
    ScreenshotReporter.takeScreenshotOrFullPage(driver, "finder_random_category", true, details);
    System.out.println("Random category: " + details);
}

/**
 * After YMM + Find, verifies the product finder: waits for a listing or empty message,
 * counts visible product items, screenshot as pass/fail for reporting.
 */
public static void verifyProductFinder(WebDriver driver) throws Exception {
    int timeoutSec = ShopdapConfig.getInt("productFinderTimeoutSeconds", 25);
    int postWait = ShopdapConfig.getInt("productFinderPostFindWaitMs", 500);
    int minItems = ShopdapConfig.getInt("productFinderMinItems", 1);
    FluentWait<WebDriver> wait = WebDriverWaits.waitDriver(driver, timeoutSec);

    Thread.sleep(postWait);
    WebDriverWaits.waitForPageToLoad(driver);

    if (productFinderNoResultsVisible(driver)) {
        String details = "Empty / no-results message visible. URL: " + driver.getCurrentUrl();
        ScreenshotReporter.takeScreenshotOrFullPage(driver, "product_finder", false, details);
        System.out.println("Product finder: FAIL — " + details);
        return;
    }

    try {
        wait.until(d -> countVisibleProductItems(d) >= minItems || productFinderNoResultsVisible(d));
    } catch (TimeoutException e) {
        System.out.println("Product finder: timed out waiting for listing (timeout " + timeoutSec + "s).");
    }

    int count = countVisibleProductItems(driver);
    boolean emptyMsg = productFinderNoResultsVisible(driver);
    boolean pass = !emptyMsg && count >= minItems;
    String details = String.format(
            "%s | URL: %s | visible product items: %d (min required: %d)",
            pass ? "Listing detected" : (emptyMsg ? "No-results state" : "Insufficient or no product tiles"),
            driver.getCurrentUrl(),
            count,
            minItems);
    ScreenshotReporter.takeScreenshotOrFullPage(driver, "product_finder", pass, details);
    System.out.println("Product finder: " + (pass ? "PASS — " : "FAIL — ") + details);
}

/** Magento empty-state messages when the finder returns no products. */
private static boolean productFinderNoResultsVisible(WebDriver driver) {
    List<By> noResultHints = List.of(
            By.cssSelector(".message.info.empty"),
            By.xpath("//*[contains(@class,'message')][contains(.,\"don't have any\")]"),
            By.xpath("//*[contains(@class,'message')][contains(.,\"We couldn't find\")]"),
            By.xpath("//*[contains(@class,'message')][contains(.,\"no products\")]"));
    for (By by : noResultHints) {
        for (WebElement el : driver.findElements(by)) {
            try {
                if (el.isDisplayed()) return true;
            } catch (Exception ignored) { }
        }
    }
    return false;
}

/** Counts visible {@code .product-item} tiles (tries several common Magento grid wrappers). */
public static int countVisibleProductItems(WebDriver driver) {
    List<By> itemSelectors = List.of(
            By.cssSelector("li.product-item"),
            By.cssSelector(".products.list.items .product-item"),
            By.cssSelector(".products-grid .product-item"),
            By.cssSelector(".product-items .product-item"),
            By.cssSelector("[data-container='product-grid'] .product-item"),
            By.cssSelector("ol.products li.item.product"));
    int n = 0;
    for (By by : itemSelectors) {
        for (WebElement el : driver.findElements(by)) {
            try {
                if (el.isDisplayed()) n++;
            } catch (Exception ignored) { }
        }
        if (n > 0) return n;
    }
    return n;
}
}
