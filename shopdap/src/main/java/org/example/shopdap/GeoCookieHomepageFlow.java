package org.example.shopdap;

import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.FluentWait;

import java.util.List;

/** Geo / location modal, cookie consent, homepage screenshot, then YMM. */
public final class GeoCookieHomepageFlow {
    private GeoCookieHomepageFlow() {}

/** XPath order matters: more specific Magento modal Close buttons first. */
private static final List<By> GEO_CLOSE_PATHS = List.of(
        By.xpath("//div[contains(@class,'modal-popup') and contains(@class,'_show')]//button[.//span[normalize-space()='Close']]"),
        By.xpath("//div[contains(@class,'modal-inner-wrap')]//button[.//span[normalize-space()='Close']]"),
        By.xpath("//span[normalize-space()='Close']/ancestor::button[1]"),
        By.xpath("//button[normalize-space(.)='Close']"),
        By.xpath("//button[.//span[normalize-space()='Close']]"));

/** Returns whether an element looks visible to the user (size, opacity, modal _show). */
private static final String JS_CONTROL_VISIBLE =
        "var el=arguments[0];if(!el)return false;var s=getComputedStyle(el),r=el.getBoundingClientRect();"
                + "var box=r.width>0&&r.height>0&&s.visibility!=='hidden'&&s.display!=='none'&&parseFloat(s.opacity)>0;"
                + "var pop=el.closest('.modal-popup');var shown=!pop||pop.classList.contains('_show');return box&&shown;";

/** When Selenium cannot find geo Close, click the first visible Magento-style close in the DOM. */
private static final String JS_FALLBACK_CLOSE =
        "function vis(el){var st=getComputedStyle(el),r=el.getBoundingClientRect();"
                + "return r.width>0&&r.height>0&&st.visibility!=='hidden'&&st.display!=='none';}"
                + "var sels=['button.action-close[data-role=\"closeBtn\"]','.modal-popup._show button.action-close'];"
                + "for(var k=0;k<sels.length;k++){var btns=document.querySelectorAll(sels[k]);"
                + "for(var i=0;i<btns.length;i++){if(vis(btns[i])){btns[i].click();return true;}}}"
                + "var all=document.querySelectorAll('button');"
                + "for(var j=0;j<all.length;j++){var b=all[j];"
                + "if((b.textContent||'').trim()==='Close'&&vis(b)){b.click();return true;}}return false;";

/** Cookie banners vary; first match wins. */
private static final By[] COOKIE_ACCEPT = {
        By.xpath("//button[contains(.,'ACCEPT')]"),
        By.xpath("//a[contains(.,'ACCEPT')]"),
        By.xpath("//*[contains(.,'ACCEPT') and (self::button or self::a)]"),
        By.xpath("//button[.//span[normalize-space()='Accept']]"),
        By.xpath("//span[normalize-space()='Accept']"),
        By.xpath("//a[normalize-space()='Accept']"),
        By.xpath("//button[contains(.,'Accept')]")
};

public static void run(WebDriver driver) throws Exception {
    JavascriptExecutor js = (JavascriptExecutor) driver;
    int geoWaitSec = ShopdapConfig.getInt("geoPopupTimeoutSeconds", 20);
    FluentWait<WebDriver> waitGeo = WebDriverWaits.waitDriver(driver, geoWaitSec);

    try {
        WebElement closeBtn = waitGeo.until(d -> findVisibleGeoPopupClose(d, js));
        WebElement modalRef = (WebElement) js.executeScript(
                "var b=arguments[0];return(b&&b.closest)?b.closest('.modal-inner-wrap'):null;", closeBtn);
        final WebElement modalForImages = modalRef;
        waitGeo.until(d -> imagesLoadedInModal(d, js, modalForImages));
        Thread.sleep(250);
        ScreenshotReporter.takeScreenshotOrFullPage(driver, "geo_popup");
        try {
            js.executeScript("arguments[0].scrollIntoView({block:'center'});", closeBtn);
            Thread.sleep(120);
            js.executeScript("arguments[0].click();", closeBtn);
        } catch (StaleElementReferenceException e) {
            WebElement again = findVisibleGeoPopupClose(driver, js);
            if (again != null) js.executeScript("arguments[0].click();", again);
        } catch (Exception e) {
            for (WebElement ok : driver.findElements(By.cssSelector(".modal-footer button[data-role='action']"))) {
                if (ok.isDisplayed()) {
                    js.executeScript("arguments[0].click();", ok);
                    break;
                }
            }
        }
        Thread.sleep(350);
        System.out.println("Geo popup closed.");
    } catch (TimeoutException e) {
        if (Boolean.TRUE.equals(js.executeScript(JS_FALLBACK_CLOSE))) {
            Thread.sleep(350);
            System.out.println("Geo popup closed (JS fallback).");
        } else {
            System.out.println("Geo popup did not appear; continuing.");
        }
    }

    Thread.sleep(350);
    FluentWait<WebDriver> waitCookie = WebDriverWaits.waitDriver(driver, 10);
    for (By by : COOKIE_ACCEPT) {
        try {
            WebElement el = waitCookie.until(ExpectedConditions.elementToBeClickable(by));
            js.executeScript("arguments[0].scrollIntoView({block:'center'});", el);
            Thread.sleep(120);
            try {
                el.click();
            } catch (Exception e) {
                js.executeScript("arguments[0].click();", el);
            }
            Thread.sleep(250);
            System.out.println("Cookie accepted.");
            break;
        } catch (Exception ignored) { }
    }

    try {
        WebDriverWaits.waitDriver(driver, 8).until(d -> {
            for (WebElement e : d.findElements(By.cssSelector(".modal-popup._show, .modals-overlay"))) {
                try {
                    if (e.isDisplayed()) return false;
                } catch (Exception ignored) { }
            }
            return true;
        });
    } catch (Exception ignored) { }
    WebDriverWaits.waitForPageToLoad(driver);
    Thread.sleep(450);
    ScreenshotReporter.takeScreenshotOrFullPage(driver, "homepage");
    

    YmmVehicleFinder.run(driver, js);
}
/** First visible Close control inside the geo / location popup. */
private static WebElement findVisibleGeoPopupClose(WebDriver driver, JavascriptExecutor js) {
    for (By by : GEO_CLOSE_PATHS) {
        for (WebElement btn : driver.findElements(by)) {
            if (isGeoControlVisible(btn, js)) return btn;
        }
    }
    for (WebElement btn : driver.findElements(By.cssSelector(
            "button.action-close[data-role='closeBtn'], .modal-popup._show button.action-close, button.action-close"))) {
        if (isGeoControlVisible(btn, js)) return btn;
    }
    return null;
}

/** Uses {@link WebElement#isDisplayed()} or {@link #JS_CONTROL_VISIBLE} for partially hidden Magento buttons. */
private static boolean isGeoControlVisible(WebElement btn, JavascriptExecutor js) {
    try {
        if (btn.isDisplayed()) return true;
        return Boolean.TRUE.equals(js.executeScript(JS_CONTROL_VISIBLE, btn));
    } catch (Exception e) {
        return false;
    }
}

/** Waits for geo popup images to finish loading before screenshot (avoids half-painted modal). */
private static boolean imagesLoadedInModal(WebDriver d, JavascriptExecutor js, WebElement modal) {
    try {
        if (modal == null) return true;
        List<WebElement> imgs = modal.findElements(By.cssSelector(
                "#geopopup img, [data-modal='geopopup'] img, .modal-content img"));
        if (imgs.isEmpty()) return true;
        for (WebElement img : imgs) {
            Object ok = js.executeScript("return arguments[0].complete && arguments[0].naturalHeight > 0", img);
            if (!Boolean.TRUE.equals(ok)) return false;
        }
        return true;
    } catch (Exception e) {
        return false;
    }
}
}
