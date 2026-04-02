package org.example.shopdap;

import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.support.ui.FluentWait;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;

/**
 * Shared {@link FluentWait} (see {@code waitPollIntervalMs} in config) and document-ready wait.
 */
public final class WebDriverWaits {

    private WebDriverWaits() {}

    public static FluentWait<WebDriver> waitDriver(WebDriver driver, long timeoutSeconds) {
        int pollMs = ShopdapConfig.getInt("waitPollIntervalMs", 150);
        if (pollMs < 50) {
            pollMs = 50;
        }
        return new WebDriverWait(driver, Duration.ofSeconds(timeoutSeconds))
                .pollingEvery(Duration.ofMillis(pollMs));
    }

    public static void waitForPageToLoad(WebDriver driver) {
        int secs = ShopdapConfig.getInt("pageLoadTimeout", 30);
        FluentWait<WebDriver> wait = waitDriver(driver, secs);
        try {
            wait.until(wd -> ((JavascriptExecutor) wd).executeScript("return document.readyState").equals("complete"));
            wait.until(wd -> ((Long) ((JavascriptExecutor) wd).executeScript(
                    "return window.jQuery != undefined && jQuery.active == 0 ? 1 : 0")) == 1);
        } catch (Exception e) {
            System.out.println("Page load wait: " + e.getMessage());
        }
    }
}
