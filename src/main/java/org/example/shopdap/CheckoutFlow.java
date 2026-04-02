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

import java.io.IOException;
import java.util.List;

/**
 * Magento 2 one-page checkout: from cart, {@code Proceed to Checkout}, guest email + shipping address,
 * optional first shipping method, screenshots. Does <strong>not</strong> submit payment or place an order.
 */
public final class CheckoutFlow {

    private CheckoutFlow() {}

    public static void run(WebDriver driver) throws Exception {
        if (!ShopdapConfig.get("checkoutFlowEnabled", "false").equalsIgnoreCase("true")) {
            System.out.println("Checkout flow skipped (checkoutFlowEnabled=false).");
            return;
        }
        JavascriptExecutor js = (JavascriptExecutor) driver;
        String base = ShopdapConfig.get("baseUrl", "https://www.shopdap.com/").replaceAll("/+$", "");
        String cartPath = ShopdapConfig.get("checkoutCartPath", "/checkout/cart/");
        if (!cartPath.startsWith("/")) {
            cartPath = "/" + cartPath;
        }
        int timeoutSec = ShopdapConfig.getInt("checkoutTimeoutSeconds", 60);

        driver.get(base + cartPath);
        WebDriverWaits.waitForPageToLoad(driver);
        Thread.sleep(ShopdapConfig.getInt("checkoutAfterCartLoadMs", 900));

        if (!cartHasLineItems(driver)) {
            System.out.println("Checkout flow: cart appears empty — open cart with items before checkout. Skipping.");
            return;
        }

        ScreenshotReporter.takeScreenshotOrFullPage(driver, "checkout_cart_before_proceed", true,
                "Cart before Proceed to Checkout | " + driver.getCurrentUrl());

        try {
            clickProceedToCheckout(driver, js, timeoutSec);
        } catch (TimeoutException e) {
            System.out.println("Checkout flow: Proceed to Checkout not found or not clickable. Skipping.");
            return;
        }

        WebDriverWaits.waitForPageToLoad(driver);
        Thread.sleep(ShopdapConfig.getInt("checkoutAfterProceedWaitMs", 2000));

        try {
            WebDriverWaits.waitDriver(driver, timeoutSec).until(d -> checkoutShellVisible(d));
        } catch (TimeoutException e) {
            System.out.println("Checkout flow: checkout UI not detected after proceed. Skipping fill.");
            ScreenshotReporter.takeScreenshotOrFullPage(driver, "checkout_proceed_timeout", false,
                    "Checkout shell not found | " + driver.getCurrentUrl());
            return;
        }

        waitCheckoutAjaxQuiet(driver, timeoutSec);
        ScreenshotReporter.takeScreenshotOrFullPage(driver, "checkout_shipping_step_loaded", true,
                "Checkout loaded | " + driver.getCurrentUrl());

        try {
            fillGuestEmailIfPresent(driver, js, timeoutSec);
            waitCheckoutAjaxQuiet(driver, timeoutSec);
            fillShippingAddress(driver, js, timeoutSec);
            waitCheckoutAjaxQuiet(driver, timeoutSec);
            waitForShippingRatesToSettle(driver, timeoutSec);
            selectFirstShippingMethodIfAny(driver, js);
            waitCheckoutAjaxQuiet(driver, timeoutSec);
            Thread.sleep(ShopdapConfig.getInt("checkoutAfterShippingFillWaitMs", 2000));
        } catch (NoSuchElementException | TimeoutException e) {
            System.out.println("Checkout flow: could not complete shipping form — " + e.getMessage());
        }

        try {
            js.executeScript(
                    "var el=document.querySelector('#checkout-shipping-method-load');"
                            + "if(el) el.scrollIntoView({block:'center'});");
            Thread.sleep(400);
            ScreenshotReporter.takeScreenshotOrFullPage(driver, "checkout_shipping_rates_ready", true,
                    ShopdapConfig.get("checkoutGuestEmail", "") + " | "
                            + ShopdapConfig.get("checkoutShipCity", "") + " "
                            + ShopdapConfig.get("checkoutShipPostcode", "")
                            + " | shipping rates (no place order) | " + driver.getCurrentUrl());
        } catch (IOException e) {
            System.out.println("Checkout screenshot: " + e.getMessage());
        }

        System.out.println("Checkout flow finished (no order placed). URL: " + driver.getCurrentUrl());
    }

    private static boolean cartHasLineItems(WebDriver driver) {
        for (WebElement row : driver.findElements(By.cssSelector("#shopping-cart-table tbody tr, .cart.item"))) {
            try {
                if (row.isDisplayed()) {
                    return true;
                }
            } catch (Exception ignored) {
                // next
            }
        }
        return driver.findElements(By.cssSelector(".cart.table-wrapper .col.qty, input.cart-item-qty")).stream()
                .anyMatch(e -> {
                    try {
                        return e.isDisplayed();
                    } catch (Exception ex) {
                        return false;
                    }
                });
    }

    private static boolean checkoutShellVisible(WebDriver driver) {
        for (WebElement e : driver.findElements(By.cssSelector(
                "#checkout, .opc-wrapper, .checkout-index-index, #shipping, .checkout-shipping-address, "
                        + "#checkout-step-shipping, li#Onepagecheckout-shipping"))) {
            try {
                if (e.isDisplayed()) {
                    return true;
                }
            } catch (Exception ignored) {
                // next
            }
        }
        return false;
    }

    /**
     * Waits for Magento to expose shipping methods (radios or table), loading masks cleared, jQuery idle; then configurable settle.
     */
    private static void waitForShippingRatesToSettle(WebDriver driver, int timeoutSec) throws InterruptedException {
        int rateWaitSec = ShopdapConfig.getInt("checkoutShippingRatesTimeoutSeconds", 50);
        FluentWait<WebDriver> w = WebDriverWaits.waitDriver(driver, rateWaitSec);
        try {
            w.until(d -> {
                if (!Boolean.TRUE.equals(((JavascriptExecutor) d).executeScript(
                        "return typeof jQuery === 'undefined' || jQuery.active === 0"))) {
                    return false;
                }
                for (WebElement m : d.findElements(By.cssSelector(
                        "#checkout-shipping-method-load .loading-mask, "
                                + "#shipping-method-buttons-container .loading-mask"))) {
                    try {
                        if (m.isDisplayed()) {
                            return false;
                        }
                    } catch (StaleElementReferenceException e) {
                        return false;
                    }
                }
                for (WebElement r : d.findElements(By.cssSelector("#checkout-shipping-method-load input[type='radio']"))) {
                    try {
                        if (r.isDisplayed()) {
                            return true;
                        }
                    } catch (StaleElementReferenceException e) {
                        // keep polling
                    }
                }
                for (WebElement row : d.findElements(By.cssSelector(
                        "#checkout-shipping-method-load .table-checkout-shipping-method tr, "
                                + "#checkout-shipping-method-load .row"))) {
                    try {
                        if (row.isDisplayed()) {
                            return true;
                        }
                    } catch (StaleElementReferenceException e) {
                        // next
                    }
                }
                return false;
            });
        } catch (TimeoutException e) {
            System.out.println("Checkout: shipping rates not detected within " + rateWaitSec + "s — capturing anyway.");
        }
        waitCheckoutAjaxQuiet(driver, timeoutSec);
        Thread.sleep(ShopdapConfig.getInt("checkoutAfterShippingRatesWaitMs", 3500));
    }

    private static void waitCheckoutAjaxQuiet(WebDriver driver, int timeoutSec) {
        FluentWait<WebDriver> w = WebDriverWaits.waitDriver(driver, timeoutSec);
        try {
            w.until(d -> Boolean.TRUE.equals(((JavascriptExecutor) d).executeScript(
                    "return typeof jQuery === 'undefined' || jQuery.active === 0")));
        } catch (Exception ignored) {
            // continue
        }
        try {
            w.until(d -> {
                for (WebElement m : d.findElements(By.cssSelector(
                        ".loading-mask, .opc-block-shipping-information .loading-mask, "
                                + "#checkout-shipping-method-load .loading-mask"))) {
                    try {
                        if (m.isDisplayed()) {
                            return false;
                        }
                    } catch (StaleElementReferenceException e) {
                        return false;
                    }
                }
                return true;
            });
        } catch (Exception ignored) {
            // continue
        }
    }

    private static void clickProceedToCheckout(WebDriver driver, JavascriptExecutor js, int timeoutSec)
            throws InterruptedException {
        // Cart page: TestRigor-style //span[normalize-space()="Proceed to Checkout"] — prefer parent a/button, then span.
        List<By> locators = List.of(
                By.xpath("//span[normalize-space()='Proceed to Checkout']/ancestor::a[1]"),
                By.xpath("//span[normalize-space()='Proceed to Checkout']/ancestor::button[1]"),
                By.xpath("//span[normalize-space()='Proceed to Checkout']"),
                By.cssSelector("button[data-role='proceed-to-checkout']"),
                By.cssSelector("a.action.primary.checkout"),
                By.cssSelector("li.item a.checkout-button"));
        FluentWait<WebDriver> wait = WebDriverWaits.waitDriver(driver, timeoutSec);
        WebElement btn = wait.until(d -> {
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
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", btn);
        Thread.sleep(ShopdapConfig.getInt("checkoutBeforeProceedClickMs", 300));
        try {
            btn.click();
        } catch (Exception e) {
            js.executeScript("arguments[0].click();", btn);
        }
    }

    private static void fillGuestEmailIfPresent(WebDriver driver, JavascriptExecutor js, int timeoutSec)
            throws InterruptedException {
        WebElement email;
        try {
            email = WebDriverWaits.waitDriver(driver, Math.min(25, timeoutSec)).until(d -> {
                for (WebElement e : d.findElements(By.id("customer-email"))) {
                    try {
                        if (e.isDisplayed()) {
                            return e;
                        }
                    } catch (Exception ignored) {
                        // next
                    }
                }
                return null;
            });
        } catch (TimeoutException e) {
            System.out.println("Checkout: #customer-email not found (logged-in checkout?).");
            return;
        }
        String value = ShopdapConfig.get("checkoutGuestEmail", "").trim();
        if (value.isEmpty()) {
            System.out.println("Checkout: checkoutGuestEmail empty — skipping email field.");
            return;
        }
        safeClick(driver, js, email);
        fillTextInput(js, email, value);
        email.sendKeys(Keys.TAB);
        Thread.sleep(ShopdapConfig.getInt("checkoutAfterEmailWaitMs", 800));
    }

    private static void fillShippingAddress(WebDriver driver, JavascriptExecutor js, int timeoutSec)
            throws InterruptedException {
        By formBy = By.id("shipping-new-address-form");
        try {
            WebDriverWaits.waitDriver(driver, Math.min(30, timeoutSec)).until(d -> {
                for (WebElement f : d.findElements(formBy)) {
                    try {
                        if (f.isDisplayed()) {
                            return true;
                        }
                    } catch (Exception ignored) {
                        // next
                    }
                }
                return false;
            });
        } catch (TimeoutException e) {
            System.out.println("Checkout: #shipping-new-address-form not visible.");
            throw e;
        }

        js.executeScript(
                "var s=document.querySelector('#checkout-step-shipping, #Onepagecheckout-shipping');"
                        + "if(s) s.scrollIntoView({block:'start'});");
        Thread.sleep(300);

        // MageBees / inline form order: name → street → country → city → zip → state → phone (matches DOM)
        setShippingInput(driver, js, "firstname", ShopdapConfig.get("checkoutShipFirstname", "Test"));
        setShippingInput(driver, js, "lastname", ShopdapConfig.get("checkoutShipLastname", "User"));
        setShippingInput(driver, js, "street[0]", ShopdapConfig.get("checkoutShipStreet", "123 Main St"));

        String countryId = ShopdapConfig.get("checkoutShipCountryId", "US");
        WebElement country = driver.findElement(By.cssSelector("#shipping-new-address-form select[name='country_id']"));
        new Select(country).selectByValue(countryId);
        js.executeScript("arguments[0].dispatchEvent(new Event('change',{bubbles:true}));", country);
        Thread.sleep(ShopdapConfig.getInt("checkoutAfterCountrySelectMs", 900));

        setShippingInput(driver, js, "city", ShopdapConfig.get("checkoutShipCity", "Los Angeles"));
        setShippingInput(driver, js, "postcode", ShopdapConfig.get("checkoutShipPostcode", "90001"));
        WebElement postcode = driver.findElement(By.cssSelector("#shipping-new-address-form input[name='postcode']"));
        postcode.sendKeys(Keys.TAB);
        Thread.sleep(500);

        String regionId = ShopdapConfig.get("checkoutShipRegionId", "12");
        try {
            WebElement region = driver.findElement(By.cssSelector("#shipping-new-address-form select[name='region_id']"));
            new Select(region).selectByValue(regionId);
            js.executeScript("arguments[0].dispatchEvent(new Event('change',{bubbles:true}));", region);
        } catch (NoSuchElementException e) {
            WebElement regionText = driver.findElement(By.cssSelector("#shipping-new-address-form input[name='region']"));
            fillTextInput(js, regionText, ShopdapConfig.get("checkoutShipRegionText", "California"));
        }
        Thread.sleep(400);

        setShippingInput(driver, js, "telephone", ShopdapConfig.get("checkoutShipTelephone", "3105550100"));
        String company = ShopdapConfig.get("checkoutShipCompany", "");
        if (!company.isBlank()) {
            setShippingInput(driver, js, "company", company);
        }
    }

    private static void setShippingInput(WebDriver driver, JavascriptExecutor js, String nameAttr, String value)
            throws InterruptedException {
        if (value == null || value.isBlank()) {
            return;
        }
        WebElement el = driver.findElement(
                By.cssSelector("#shipping-new-address-form input[name='" + nameAttr + "']"));
        safeClick(driver, js, el);
        fillTextInput(js, el, value);
    }

    private static void selectFirstShippingMethodIfAny(WebDriver driver, JavascriptExecutor js)
            throws InterruptedException {
        List<WebElement> radios = driver.findElements(
                By.cssSelector("#checkout-shipping-method-load input[type='radio'][name^='ko_unique']"));
        if (radios.isEmpty()) {
            radios = driver.findElements(By.cssSelector("#checkout-shipping-method-load input[type='radio']"));
        }
        for (WebElement r : radios) {
            try {
                if (r.isDisplayed() && r.isEnabled()) {
                    js.executeScript("arguments[0].scrollIntoView({block:'center'});", r);
                    Thread.sleep(150);
                    try {
                        r.click();
                    } catch (Exception e) {
                        js.executeScript("arguments[0].click();", r);
                    }
                    return;
                }
            } catch (Exception ignored) {
                // next radio
            }
        }
    }

    private static void safeClick(WebDriver driver, JavascriptExecutor js, WebElement el) {
        try {
            js.executeScript("arguments[0].scrollIntoView({block:'center'});", el);
            el.click();
        } catch (Exception e) {
            js.executeScript("arguments[0].click();", el);
        }
    }

    private static void fillTextInput(JavascriptExecutor js, WebElement element, String text) {
        js.executeScript(
                "var e=arguments[0], t=arguments[1];"
                        + "e.removeAttribute('readonly'); e.removeAttribute('disabled');"
                        + "e.focus(); e.value=t;"
                        + "e.dispatchEvent(new Event('input',{bubbles:true}));"
                        + "e.dispatchEvent(new Event('change',{bubbles:true}));"
                        + "e.dispatchEvent(new Event('blur',{bubbles:true}));",
                element, text);
    }
}
