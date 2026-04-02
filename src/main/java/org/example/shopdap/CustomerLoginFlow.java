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

import java.io.IOException;
import java.util.List;

/**
 * Magento customer login at {@code /customer/account/login/}: {@code //input[@id="email"]}, {@code #pass}, Sign In.
 * Intended to run <strong>after</strong> {@link CheckoutFlow} in {@link Shopdap#main}. Optional: homepage category tile
 * + first product “Add to Wishlist” ({@code postLoginHomepageWishlistEnabled}), then PDP “Add to Compare” and
 * “comparison list” ({@code postLoginCompareEnabled}). Credentials only in {@code config.properties}.
 */
public final class CustomerLoginFlow {

    private CustomerLoginFlow() {}

    public static void run(WebDriver driver) throws Exception {
        if (!ShopdapConfig.get("customerLoginFlowEnabled", "false").equalsIgnoreCase("true")) {
            System.out.println("Customer login flow skipped (customerLoginFlowEnabled=false).");
            return;
        }
        String email = ShopdapConfig.get("customerLoginEmail", "").trim();
        String password = ShopdapConfig.get("customerLoginPassword", "");
        if (email.isEmpty() || password == null || password.isEmpty()) {
            System.out.println("Customer login: set customerLoginEmail and customerLoginPassword in config.properties.");
            return;
        }

        JavascriptExecutor js = (JavascriptExecutor) driver;
        String loginUrl = ShopdapConfig.get("customerLoginUrl", "").trim();
        if (loginUrl.isEmpty()) {
            String base = ShopdapConfig.get("baseUrl", "https://www.shopdap.com/").replaceAll("/+$", "");
            loginUrl = base + "/customer/account/login/";
        }

        int timeoutSec = ShopdapConfig.getInt("customerLoginTimeoutSeconds", 25);
        Thread.sleep(ShopdapConfig.getInt("customerLoginAfterCheckoutWaitMs", 600));
        driver.get(loginUrl);
        WebDriverWaits.waitForPageToLoad(driver);
        Thread.sleep(ShopdapConfig.getInt("customerLoginPageSettleMs", 500));

        // TestRigor-style: //input[@id="email"] ; password field id="pass"
        WebElement emailField = findFirstDisplayed(driver, timeoutSec, List.of(
                By.xpath("//input[@id='email']"),
                By.id("email"),
                By.cssSelector("input[name='login[username]']"),
                By.cssSelector("form.form-login input[type='email']")));
        WebElement passField = findFirstDisplayed(driver, timeoutSec, List.of(
                By.xpath("//input[@id='pass']"),
                By.id("pass"),
                By.id("password"),
                By.cssSelector("input[name='login[password]']"),
                By.cssSelector("form.form-login input[type='password']")));

        js.executeScript("arguments[0].scrollIntoView({block:'center'});", emailField);
        Thread.sleep(ShopdapConfig.getInt("customerLoginBeforeTypeMs", 200));
        try {
            emailField.click();
        } catch (Exception e) {
            js.executeScript("arguments[0].click();", emailField);
        }
        emailField.sendKeys(Keys.chord(Keys.CONTROL, "a"), Keys.BACK_SPACE);
        emailField.sendKeys(email);

        js.executeScript("arguments[0].scrollIntoView({block:'center'});", passField);
        Thread.sleep(150);
        try {
            passField.click();
        } catch (Exception e) {
            js.executeScript("arguments[0].click();", passField);
        }
        passField.sendKeys(Keys.chord(Keys.CONTROL, "a"), Keys.BACK_SPACE);
        passField.sendKeys(password);

        WebElement submit = findSignInButton(driver, timeoutSec);
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", submit);
        Thread.sleep(ShopdapConfig.getInt("customerLoginBeforeSubmitMs", 250));
        try {
            submit.click();
        } catch (Exception e) {
            js.executeScript("arguments[0].click();", submit);
        }

        boolean loggedIn = waitForLoginOutcome(driver, timeoutSec);
        String details = (loggedIn ? "Logged in" : "Login failed or still on login")
                + " | " + driver.getCurrentUrl();
        try {
            ScreenshotReporter.takeScreenshotOrFullPage(driver,
                    loggedIn ? "customer_login_success" : "customer_login_result",
                    loggedIn,
                    details);
        } catch (IOException e) {
            System.out.println("Customer login screenshot: " + e.getMessage());
        }
        System.out.println("Customer login: " + details);

        if (loggedIn && ShopdapConfig.get("postLoginHomepageWishlistEnabled", "false").equalsIgnoreCase("true")) {
            try {
                openHomeCategoryAndAddToWishlist(driver, js);
            } catch (Exception e) {
                System.out.println("Post-login home category / wishlist: " + e.getMessage());
            }
        }
    }

    /**
     * Homepage {@code div.category-view.home-category} → click a dynamic tile ({@code li[url]}), then first product
     * “Add to Wishlist” ({@code a.action.towishlist} / span “Add to Wishlist”).
     */
    private static void openHomeCategoryAndAddToWishlist(WebDriver driver, JavascriptExecutor js) throws Exception {
        int timeoutSec = ShopdapConfig.getInt("postLoginWishlistTimeoutSeconds", 25);
        String base = ShopdapConfig.get("baseUrl", "https://www.shopdap.com/").replaceAll("/+$", "") + "/";
        driver.get(base);
        WebDriverWaits.waitForPageToLoad(driver);
        Thread.sleep(ShopdapConfig.getInt("postLoginHomepageSettleMs", 900));

        WebDriverWaits.waitDriver(driver, timeoutSec).until(d -> {
            for (WebElement el : d.findElements(By.cssSelector("div.category-view.home-category, .home-category"))) {
                try {
                    if (el.isDisplayed()) {
                        return true;
                    }
                } catch (Exception ignored) {
                    // next
                }
            }
            return false;
        });

        WebElement categoryLink = findHomeCategoryTileLink(driver, timeoutSec);
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", categoryLink);
        Thread.sleep(ShopdapConfig.getInt("postLoginBeforeCategoryClickMs", 400));
        try {
            categoryLink.click();
        } catch (Exception e) {
            js.executeScript("arguments[0].click();", categoryLink);
        }
        WebDriverWaits.waitForPageToLoad(driver);
        Thread.sleep(ShopdapConfig.getInt("postLoginAfterCategoryLoadMs", 1500));

        try {
            ScreenshotReporter.takeScreenshotOrFullPage(driver, "post_login_category_listing", true,
                    "After home category tile | " + driver.getCurrentUrl());
        } catch (IOException e) {
            System.out.println("Post-login category screenshot: " + e.getMessage());
        }

        WebElement wish = findFirstAddToWishlistOnListing(driver, timeoutSec);
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", wish);
        Thread.sleep(ShopdapConfig.getInt("postLoginBeforeWishlistClickMs", 350));
        try {
            wish.click();
        } catch (Exception e) {
            js.executeScript("arguments[0].click();", wish);
        }
        WebDriverWaits.waitForPageToLoad(driver);
        Thread.sleep(ShopdapConfig.getInt("postLoginAfterWishlistClickMs", 1200));
        try {
            ScreenshotReporter.takeScreenshotOrFullPage(driver, "post_login_add_to_wishlist", true,
                    "Add to Wishlist | " + driver.getCurrentUrl());
        } catch (IOException e) {
            System.out.println("Post-login wishlist screenshot: " + e.getMessage());
        }
        System.out.println("Post-login: Add to Wishlist clicked. URL: " + driver.getCurrentUrl());

        if (ShopdapConfig.get("postLoginCompareEnabled", "false").equalsIgnoreCase("true")) {
            openPdpAddCompareOpenComparisonList(driver, js);
        }
    }

    /**
     * Listing → first product PDP → “Add to Compare” → link “comparison list” / compare page → full-page screenshot.
     */
    private static void openPdpAddCompareOpenComparisonList(WebDriver driver, JavascriptExecutor js) throws Exception {
        int timeoutSec = ShopdapConfig.getInt("postLoginCompareTimeoutSeconds", 25);
        WebElement pdpLink = findFirstListingProductLink(driver, timeoutSec);
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", pdpLink);
        Thread.sleep(ShopdapConfig.getInt("postLoginBeforePdpClickMs", 400));
        try {
            pdpLink.click();
        } catch (Exception e) {
            js.executeScript("arguments[0].click();", pdpLink);
        }
        WebDriverWaits.waitForPageToLoad(driver);
        Thread.sleep(ShopdapConfig.getInt("postLoginAfterPdpLoadMs", 900));

        WebElement addCompare = findAddToCompareOnPdp(driver, timeoutSec);
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", addCompare);
        Thread.sleep(ShopdapConfig.getInt("postLoginBeforeAddCompareClickMs", 350));
        try {
            addCompare.click();
        } catch (Exception e) {
            js.executeScript("arguments[0].click();", addCompare);
        }
        WebDriverWaits.waitForPageToLoad(driver);
        Thread.sleep(ShopdapConfig.getInt("postLoginAfterAddCompareMs", 1200));

        WebElement comparisonList = findComparisonListLink(driver, timeoutSec);
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", comparisonList);
        Thread.sleep(ShopdapConfig.getInt("postLoginBeforeComparisonListClickMs", 300));
        try {
            comparisonList.click();
        } catch (Exception e) {
            js.executeScript("arguments[0].click();", comparisonList);
        }
        WebDriverWaits.waitForPageToLoad(driver);
        Thread.sleep(ShopdapConfig.getInt("postLoginAfterComparisonPageMs", 1000));
        try {
            ScreenshotReporter.takeScreenshotOrFullPage(driver, "post_login_comparison_list", true,
                    "Comparison list | " + driver.getCurrentUrl());
        } catch (IOException e) {
            System.out.println("Comparison list screenshot: " + e.getMessage());
        }
        System.out.println("Post-login: comparison list page. URL: " + driver.getCurrentUrl());
    }

    private static WebElement findFirstListingProductLink(WebDriver driver, int timeoutSec) {
        FluentWait<WebDriver> wait = WebDriverWaits.waitDriver(driver, timeoutSec);
        return wait.until(d -> {
            for (WebElement tile : d.findElements(By.cssSelector(
                    ".products .product-item, ol.products li.product-item, .product-items .product-item"))) {
                try {
                    if (!tile.isDisplayed()) {
                        continue;
                    }
                } catch (Exception e) {
                    continue;
                }
                for (By by : List.of(
                        By.cssSelector("a.product-item-link"),
                        By.cssSelector(".product-item-name a"),
                        By.cssSelector("a.product.photo"))) {
                    for (WebElement a : tile.findElements(by)) {
                        try {
                            String href = a.getDomAttribute("href");
                            if (href == null || href.isBlank()) {
                                href = a.getAttribute("href");
                            }
                            if (href != null && !href.isBlank() && a.isDisplayed()) {
                                return a;
                            }
                        } catch (Exception ignored) {
                            // next
                        }
                    }
                }
            }
            return null;
        });
    }

    private static WebElement findAddToCompareOnPdp(WebDriver driver, int timeoutSec) {
        FluentWait<WebDriver> wait = WebDriverWaits.waitDriver(driver, timeoutSec);
        return wait.until(d -> {
            for (By by : List.of(
                    By.cssSelector("a.action.tocompare"),
                    By.cssSelector("[data-post*='compare']"),
                    By.xpath("//span[contains(.,'Add to Compare')]/ancestor::a[1]"),
                    By.xpath("//a[contains(@class,'tocompare')]"))) {
                for (WebElement el : d.findElements(by)) {
                    try {
                        if (el.isDisplayed() && el.isEnabled()) {
                            return el;
                        }
                    } catch (Exception ignored) {
                        // next
                    }
                }
            }
            return null;
        });
    }

    private static WebElement findComparisonListLink(WebDriver driver, int timeoutSec) {
        FluentWait<WebDriver> wait = WebDriverWaits.waitDriver(driver, timeoutSec);
        return wait.until(d -> {
            for (WebElement a : d.findElements(By.cssSelector("a[href*='product_compare']"))) {
                try {
                    if (a.isDisplayed()) {
                        return a;
                    }
                } catch (Exception ignored) {
                    // next
                }
            }
            for (WebElement a : d.findElements(By.xpath(
                    "//a[contains(translate(normalize-space(.),"
                            + "'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'comparison list')]"))) {
                try {
                    if (a.isDisplayed()) {
                        return a;
                    }
                } catch (Exception ignored) {
                    // next
                }
            }
            for (WebElement a : d.findElements(By.partialLinkText("Comparison"))) {
                try {
                    String t = a.getText();
                    if (a.isDisplayed() && t != null && t.toLowerCase().contains("list")) {
                        return a;
                    }
                } catch (Exception ignored) {
                    // next
                }
            }
            return null;
        });
    }

    private static WebElement findHomeCategoryTileLink(WebDriver driver, int timeoutSec) {
        String urlAttr = ShopdapConfig.get("postLoginHomeCategoryUrlAttr", "").trim();
        FluentWait<WebDriver> wait = WebDriverWaits.waitDriver(driver, timeoutSec);
        if (!urlAttr.isEmpty()) {
            String css = "div.category-view.home-category li.homepagepopup[url='" + urlAttr + "'] a.block-promo";
            try {
                return wait.until(d -> {
                    for (WebElement a : d.findElements(By.cssSelector(css))) {
                        try {
                            if (a.isDisplayed()) {
                                return a;
                            }
                        } catch (Exception ignored) {
                            // next
                        }
                    }
                    return null;
                });
            } catch (TimeoutException e) {
                System.out.println("Home category: no tile for url=" + urlAttr + ", trying first visible promo link.");
            }
        }
        return wait.until(d -> {
            for (WebElement a : d.findElements(By.cssSelector(
                    "div.category-view.home-category li.homepagepopup a.block-promo"))) {
                try {
                    String href = a.getDomAttribute("href");
                    if (href == null || href.isBlank()) {
                        href = a.getAttribute("href");
                    }
                    if (href != null && !href.isBlank() && a.isDisplayed()) {
                        return a;
                    }
                } catch (StaleElementReferenceException e) {
                    // next
                } catch (Exception ignored) {
                    // next
                }
            }
            return null;
        });
    }

    private static WebElement findFirstAddToWishlistOnListing(WebDriver driver, int timeoutSec) {
        FluentWait<WebDriver> wait = WebDriverWaits.waitDriver(driver, timeoutSec);
        try {
            return wait.until(d -> {
                for (WebElement tile : d.findElements(By.cssSelector(
                        ".products .product-item, ol.products li.product-item, .product-items .product-item"))) {
                    try {
                        if (!tile.isDisplayed()) {
                            continue;
                        }
                    } catch (Exception e) {
                        continue;
                    }
                    for (By by : List.of(
                            By.cssSelector("a.action.towishlist"),
                            By.cssSelector("[data-action='add-to-wishlist']"),
                            By.xpath(".//span[contains(.,'Add to Wishlist')]/ancestor::a[1]"))) {
                        try {
                            for (WebElement w : tile.findElements(by)) {
                                try {
                                    if (w.isDisplayed()) {
                                        return w;
                                    }
                                } catch (Exception ignored) {
                                    // next
                                }
                            }
                        } catch (Exception ignored) {
                            // next locator
                        }
                    }
                }
                for (WebElement w : d.findElements(By.xpath("//span[contains(.,'Add to Wishlist')]/ancestor::a[1]"))) {
                    try {
                        if (w.isDisplayed()) {
                            return w;
                        }
                    } catch (Exception ignored) {
                        // next
                    }
                }
                return null;
            });
        } catch (TimeoutException e) {
            throw new NoSuchElementException("Add to Wishlist not found on listing within " + timeoutSec + "s");
        }
    }

    private static WebElement findFirstDisplayed(WebDriver driver, int timeoutSec, List<By> locators) {
        FluentWait<WebDriver> wait = WebDriverWaits.waitDriver(driver, timeoutSec);
        return wait.until(d -> {
            for (By by : locators) {
                for (WebElement el : d.findElements(by)) {
                    try {
                        if (el.isDisplayed() && el.isEnabled()) {
                            return el;
                        }
                    } catch (Exception ignored) {
                        // next
                    }
                }
            }
            return null;
        });
    }

    private static WebElement findSignInButton(WebDriver driver, int timeoutSec) {
        List<By> locators = List.of(
                By.id("send2"),
                By.cssSelector("form.form-login button[type='submit']"),
                By.cssSelector("button.action.login"),
                By.xpath("//span[normalize-space()='Sign In']/ancestor::button[1]"),
                By.xpath("//button[contains(@class,'login')]"));
        return findFirstDisplayed(driver, timeoutSec, locators);
    }

    private static boolean waitForLoginOutcome(WebDriver driver, int timeoutSec) {
        String loginPath = "/customer/account/login";
        FluentWait<WebDriver> wait = WebDriverWaits.waitDriver(driver, timeoutSec);
        try {
            Boolean ok = wait.until(d -> {
                String url = "";
                try {
                    url = d.getCurrentUrl();
                } catch (Exception e) {
                    return null;
                }
                if (url.contains(loginPath)) {
                    for (WebElement err : d.findElements(By.cssSelector(".message-error, .mage-error"))) {
                        try {
                            if (err.isDisplayed()) {
                                return false;
                            }
                        } catch (Exception ignored) {
                            // next
                        }
                    }
                    return null;
                }
                if (url.contains("/customer/account")) {
                    return true;
                }
                for (WebElement el : d.findElements(By.cssSelector(".customer-name, .block-dashboard, a.action.logout"))) {
                    try {
                        if (el.isDisplayed()) {
                            return true;
                        }
                    } catch (Exception ignored) {
                        // next
                    }
                }
                return null;
            });
            return Boolean.TRUE.equals(ok);
        } catch (TimeoutException e) {
            return false;
        }
    }
}
