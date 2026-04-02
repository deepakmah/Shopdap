package org.example.shopdap;

import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.FluentWait;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** Vehicle finder: MST Chosen chain or legacy Select2-style, then Find. */
public final class YmmVehicleFinder {
    private YmmVehicleFinder() {}

    public static void run(WebDriver driver, JavascriptExecutor js) throws Exception {
        runYmmBrandSelect(driver, js);
    }

private static String xpathLiteral(String s) {
    if (s == null) return "''";
    if (!s.contains("'")) return "'" + s + "'";
    String[] parts = s.split("'", -1);
    StringBuilder sb = new StringBuilder("concat(");
    for (int i = 0; i < parts.length; i++) {
        if (i > 0) sb.append(", \"'\", ");
        sb.append("'").append(parts[i].replace("\\", "\\\\")).append("'");
    }
    sb.append(")");
    return sb.toString();
}

/** Shopdap MST finder uses jQuery Chosen: {@code .mst-finder__filter-dropdown} + {@code a.chosen-single}. */
private static boolean isMstChosenFinderPresent(WebDriver driver) {
    for (WebElement el : driver.findElements(By.cssSelector(".mst-finder__finder-filters a.chosen-single"))) {
        try {
            if (el.isDisplayed()) return true;
        } catch (Exception ignored) { }
    }
    return false;
}

/** {@code disabled} on the MST wrapper means that tier is locked; skip interacting. */
private static boolean isMstFilterDropdownEnabled(WebElement dropdown) {
    try {
        if (!dropdown.isDisplayed()) return false;
        String d = dropdown.getAttribute("disabled");
        if (d == null) return true;
        String t = d.trim();
        if (t.isEmpty()) return false;
        return "false".equalsIgnoreCase(t);
    } catch (Exception e) {
        return false;
    }
}

/** True for empty text or Chosen/Select placeholders (not a real vehicle option). */
private static boolean isPlaceholderChosenOptionText(String t) {
    if (t == null) return true;
    t = t.replaceAll("\\s+", " ").trim();
    if (t.isEmpty()) return true;
    String lower = t.toLowerCase();
    return lower.contains("please select") || lower.equals("select") || lower.equals("select...");
}

/** Maps Chosen filter id (e.g. {@code transmission}) to {@code config.properties} key (e.g. {@code ymmTransmission}). */
private static String ymmConfigForChosenFilterId(String filterId) {
    return switch (filterId) {
        case "brand" -> ShopdapConfig.get("ymmBrand", "Audi");
        case "model" -> ShopdapConfig.get("ymmModel", "A4");
        case "year" -> ShopdapConfig.get("ymmYear", "B6 (2002-2005)");
        case "engine" -> ShopdapConfig.get("ymmEngine", "1.8T");
        case "transmission" -> ShopdapConfig.get("ymmTransmission", "Auto (5 Speed)");
        case "drivetrain" -> ShopdapConfig.get("ymmDrivetrain", "AWD");
        default -> ShopdapConfig.get("ymmChosen." + filterId, "");
    };
}

/** Visible label on the Chosen trigger ({@code a.chosen-single span}). */
private static String readMstChosenCurrentLabel(WebElement filterDropdown) {
    try {
        WebElement sp = filterDropdown.findElement(By.cssSelector("a.chosen-single span"));
        String t = sp.getText();
        return t != null ? t.replaceAll("\\s+", " ").trim() : "";
    } catch (Exception e) {
        return "";
    }
}

/** Visible MST block with class {@code mst-finder__filter-dropdown} for HTML id {@code fid}. */
private static WebElement findMstFilterDropdownById(WebDriver driver, String fid) {
    for (WebElement e : driver.findElements(By.id(fid))) {
        try {
            if (!e.isDisplayed()) continue;
            String cls = e.getAttribute("class");
            if (cls != null && cls.contains("mst-finder__filter-dropdown")) return e;
        } catch (Exception ignored) { }
    }
    return null;
}

/**
 * Drives {@code .mst-finder__finder-filters} Chosen dropdowns ({@code #brand}, {@code #model}, …) per your DOM:
 * {@code .mst-finder__filter-dropdown}, {@code a.chosen-single}, {@code ul.chosen-results li.active-result}.
 * Waits for each tier to become enabled after the previous choice (Magento removes {@code disabled} on the wrapper).
 * Config: {@code ymmChosenFilterIds}, {@code ymmChosenFilterEnableSeconds}, {@code ymmAutoSelect} (default {@code true}
 * for this path), {@code ymmAutoSelectMode} ({@code random} default | {@code first}), or manual {@code ymmBrand}…{@code ymmDrivetrain}.
 * In {@code random} mode, enabled filters are always opened and a new random real option is chosen (prefers a value
 * different from the current trigger when more than one option exists).
 */
private static void runMstChosenYmmChain(WebDriver driver, JavascriptExecutor js, FluentWait<WebDriver> wait)
        throws Exception {
    int pauseAfterSelect = ShopdapConfig.getInt("ymmAfterEachSelectMs", 800);
    boolean auto = ShopdapConfig.get("ymmAutoSelect", "true").equalsIgnoreCase("true");
    String autoMode = ShopdapConfig.get("ymmAutoSelectMode", "random");
    int enableSec = ShopdapConfig.getInt("ymmChosenFilterEnableSeconds", 25);
    String chainRaw = ShopdapConfig.get("ymmChosenFilterIds", "brand,model,year,engine,transmission,drivetrain");
    List<String> filterIds = new ArrayList<>();
    for (String part : chainRaw.split(",")) {
        String id = part.trim();
        if (!id.isEmpty()) filterIds.add(id);
    }
    if (filterIds.isEmpty()) {
        throw new NoSuchElementException("YMM Chosen: ymmChosenFilterIds is empty.");
    }

    for (String filterId : filterIds) {
        final String fid = filterId;
        WebElement dropdown = findMstFilterDropdownById(driver, fid);
        if (dropdown == null) {
            System.out.println("YMM Chosen: no visible block #" + filterId + " — skip.");
            continue;
        }
        if (!isMstFilterDropdownEnabled(dropdown)) {
            String lockedLabel = readMstChosenCurrentLabel(dropdown);
            if (!isPlaceholderChosenOptionText(lockedLabel)) {
                System.out.println("YMM Chosen: \"" + filterId + "\" disabled with value \"" + lockedLabel
                        + "\" — skip (site prefilled).");
                continue;
            }
            try {
                dropdown = WebDriverWaits.waitDriver(driver, enableSec).until(d -> {
                    WebElement e = findMstFilterDropdownById(d, fid);
                    return e != null && isMstFilterDropdownEnabled(e) ? e : null;
                });
            } catch (TimeoutException e) {
                System.out.println("YMM Chosen: filter \"" + filterId + "\" stayed disabled within " + enableSec
                        + "s — skip.");
                continue;
            }
        }

        String currentLabel = readMstChosenCurrentLabel(dropdown);
        String currentNorm = currentLabel.replaceAll("\\s+", " ").trim();
        boolean randomMode = auto && "random".equalsIgnoreCase(autoMode);
        if (auto && !randomMode) {
            if (!isPlaceholderChosenOptionText(currentLabel)) {
                System.out.println("YMM Chosen: \"" + filterId + "\" already \"" + currentLabel + "\" — skip (first mode).");
                continue;
            }
        } else if (!auto) {
            String want = ymmConfigForChosenFilterId(filterId);
            if (want != null && !want.isBlank()) {
                String wantNorm = want.replaceAll("\\s+", " ").trim();
                if (currentLabel.equalsIgnoreCase(wantNorm)) {
                    System.out.println("YMM Chosen: \"" + filterId + "\" already matches config — skip.");
                    continue;
                }
            }
        }

        WebElement trigger = dropdown.findElement(By.cssSelector("a.chosen-single"));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", trigger);
        Thread.sleep(ShopdapConfig.getInt("ymmChosenOpenPauseMs", 220));
        try {
            trigger.click();
        } catch (Exception e) {
            js.executeScript("arguments[0].click();", trigger);
        }

        try {
            wait.until(d -> {
                WebElement dd;
                try {
                    dd = d.findElement(By.id(fid));
                } catch (Exception ex) {
                    return false;
                }
                for (WebElement li : dd.findElements(By.cssSelector(".chosen-drop ul.chosen-results li.active-result"))) {
                    try {
                        if (!li.isDisplayed()) continue;
                        String cls = li.getAttribute("class");
                        if (cls != null && cls.contains("disabled-result")) continue;
                        String tx = li.getText();
                        if (!isPlaceholderChosenOptionText(tx)) return true;
                    } catch (Exception ignored) { }
                }
                return false;
            });
        } catch (TimeoutException e) {
            throw new NoSuchElementException(
                    "YMM Chosen: no options opened for filter \"" + filterId + "\" (.chosen-results li.active-result).");
        }

        dropdown = driver.findElement(By.id(filterId));
        List<WebElement> options = new ArrayList<>();
        for (WebElement li : dropdown.findElements(By.cssSelector(".chosen-drop ul.chosen-results li.active-result"))) {
            try {
                if (!li.isDisplayed()) continue;
                String cls = li.getAttribute("class");
                if (cls != null && cls.contains("disabled-result")) continue;
                String tx = li.getText() != null ? li.getText().replaceAll("\\s+", " ").trim() : "";
                if (isPlaceholderChosenOptionText(tx)) continue;
                options.add(li);
            } catch (Exception ignored) { }
        }
        if (options.isEmpty()) {
            throw new NoSuchElementException("YMM Chosen: no selectable options for filter \"" + filterId + "\".");
        }

        if (randomMode && options.size() > 1 && !isPlaceholderChosenOptionText(currentNorm)) {
            List<WebElement> notSame = new ArrayList<>();
            for (WebElement li : options) {
                String tx = li.getText() != null ? li.getText().replaceAll("\\s+", " ").trim() : "";
                if (!tx.equalsIgnoreCase(currentNorm)) notSame.add(li);
            }
            if (!notSame.isEmpty()) options = notSame;
        }

        WebElement choice;
        String label;
        if (auto) {
            int idx = randomMode
                    ? ThreadLocalRandom.current().nextInt(options.size())
                    : 0;
            choice = options.get(idx);
            label = choice.getText() != null ? choice.getText().replaceAll("\\s+", " ").trim() : filterId;
        } else {
            String want = ymmConfigForChosenFilterId(filterId);
            if (want == null || want.isBlank()) {
                throw new NoSuchElementException(
                        "YMM Chosen: set config for filter \"" + filterId + "\" (e.g. ymmTransmission) or enable ymmAutoSelect.");
            }
            String wantNorm = want.replaceAll("\\s+", " ").trim();
            choice = null;
            label = wantNorm;
            for (WebElement li : options) {
                String tx = li.getText() != null ? li.getText().replaceAll("\\s+", " ").trim() : "";
                if (tx.equalsIgnoreCase(wantNorm)) {
                    choice = li;
                    label = tx;
                    break;
                }
            }
            if (choice == null) {
                throw new NoSuchElementException(
                        "YMM Chosen: no option matching \"" + wantNorm + "\" in filter \"" + filterId
                                + "\". Options: " + options.stream().map(o -> {
                                    try {
                                        return o.getText().replaceAll("\\s+", " ").trim();
                                    } catch (Exception ex) {
                                        return "?";
                                    }
                                }).toList());
            }
        }

        js.executeScript("arguments[0].scrollIntoView({block:'center'});", choice);
        Thread.sleep(120);
        try {
            choice.click();
        } catch (Exception e) {
            js.executeScript("arguments[0].click();", choice);
        }

        Thread.sleep(pauseAfterSelect);
        ScreenshotReporter.takeScreenshotOrFullPage(driver, "ymm_chosen_" + filterId + "_" + ymmScreenshotToken(label));
        System.out.println("YMM Chosen: filter \"" + filterId + "\" → \"" + label + "\".");
        WebDriverWaits.waitForPageToLoad(driver);
    }

    Thread.sleep(pauseAfterSelect);
    WebDriverWaits.waitForPageToLoad(driver);
    ymmClickFindButton(driver, js, wait);
}

/** First visible YMM placeholder control (e.g. "Please select..."). */
private static WebElement findFirstVisiblePleaseSelect(WebDriver driver, FluentWait<WebDriver> wait) {
    By[] pleaseSelectPaths = {
            By.xpath("//span[normalize-space()='Please select...']"),
            By.xpath("//div[normalize-space()='Please select...']"),
            By.xpath("//li[normalize-space()='Please select...']"),
            By.xpath("//*[normalize-space()='Please select...']"),
            By.xpath("//*[contains(normalize-space(),'Please select')]")
    };
    for (By by : pleaseSelectPaths) {
        for (WebElement el : driver.findElements(by)) {
            try {
                if (el.isDisplayed()) return el;
            } catch (Exception ignored) { }
        }
    }
    try {
        return wait.until(ExpectedConditions.elementToBeClickable(
                By.xpath("(//*[contains(normalize-space(),'Please select')])[1]")));
    } catch (Exception e) {
        return null;
    }
}

/** Legacy finder: visible “Please select…” + Select2-style lists (no MST Chosen). */
private static void runYmmLegacySelect2Style(WebDriver driver, JavascriptExecutor js, FluentWait<WebDriver> wait)
        throws Exception {
    String brand = ShopdapConfig.get("ymmBrand", "Audi");
    String model = ShopdapConfig.get("ymmModel", "A4");
    String year = ShopdapConfig.get("ymmYear", "B6 (2002-2005)");
    String engine = ShopdapConfig.get("ymmEngine", "1.8T");
    int pauseAfterSelect = ShopdapConfig.getInt("ymmAfterEachSelectMs", 800);
    String lit = xpathLiteral(brand);

    WebElement openControl = findFirstVisiblePleaseSelect(driver, wait);
    if (openControl == null) {
        throw new NoSuchElementException("YMM: no visible \"Please select...\" found.");
    }

    js.executeScript("arguments[0].scrollIntoView({block:'center'});", openControl);
    Thread.sleep(250);
    try {
        openControl.click();
    } catch (Exception e) {
        js.executeScript("arguments[0].click();", openControl);
    }

    // Wait for list / options to paint (lazy or animation)
    try {
        wait.until(ExpectedConditions.or(
                ExpectedConditions.presenceOfElementLocated(By.cssSelector("[role='listbox']")),
                ExpectedConditions.presenceOfElementLocated(By.cssSelector(".select2-results")),
                ExpectedConditions.presenceOfElementLocated(By.cssSelector("ul.select-options")),
                ExpectedConditions.presenceOfElementLocated(By.xpath("//li[normalize-space()=" + lit + "]")),
                ExpectedConditions.presenceOfElementLocated(By.xpath("//div[normalize-space()=" + lit + "]"))
        ));
    } catch (TimeoutException ignored) {
        Thread.sleep(450);
    }

    WebElement brandEl = waitForVisibleYmmOption(driver, brand, Duration.ofSeconds(15));
    if (brandEl == null) {
        throw new NoSuchElementException(
                "YMM: could not find visible option for brand \"" + brand
                        + "\" after opening \"Please select...\". Check ymmBrand in config and DOM (span/div/li).");
    }
    js.executeScript("arguments[0].scrollIntoView({block:'center'});", brandEl);
    Thread.sleep(120);
    try {
        brandEl.click();
    } catch (Exception e) {
        js.executeScript("arguments[0].click();", brandEl);
    }
    Thread.sleep(pauseAfterSelect);
    ScreenshotReporter.takeScreenshotOrFullPage(driver, "ymm_brand_" + brand.replace(' ', '_'));
    System.out.println("YMM: selected brand \"" + brand + "\".");

    Thread.sleep(ShopdapConfig.getInt("ymmAfterBrandWaitMs", 0));
    WebDriverWaits.waitForPageToLoad(driver);

    ymmClickOptionMaybeOpenPleaseSelect(driver, js, wait, model,
            "YMM: could not select model \"" + model + "\". Set ymmModel in config or check DOM.");
    Thread.sleep(pauseAfterSelect);
    ScreenshotReporter.takeScreenshotOrFullPage(driver, "ymm_model_" + ymmScreenshotToken(model));
    System.out.println("YMM: selected model \"" + model + "\".");

    // Year / generation (e.g. //span[normalize-space()="B6 (2002-2005)"])
    if (year != null && !year.isBlank()) {
        Thread.sleep(ShopdapConfig.getInt("ymmAfterModelWaitMs", 0));
        WebDriverWaits.waitForPageToLoad(driver);

        ymmClickOptionMaybeOpenPleaseSelect(driver, js, wait, year,
                "YMM: could not select year/trim \"" + year + "\". Set ymmYear in config or check DOM.");
        Thread.sleep(pauseAfterSelect);
        ScreenshotReporter.takeScreenshotOrFullPage(driver, "ymm_year_" + ymmScreenshotToken(year));
        System.out.println("YMM: selected year/trim \"" + year + "\".");
    }

    // Engine (e.g. //span[normalize-space()="1.8T"])
    if (engine != null && !engine.isBlank()) {
        Thread.sleep(ShopdapConfig.getInt("ymmAfterYearWaitMs", 0));
        WebDriverWaits.waitForPageToLoad(driver);

        ymmClickOptionMaybeOpenPleaseSelect(driver, js, wait, engine,
                "YMM: could not select engine \"" + engine + "\". Set ymmEngine in config or check DOM.");
        Thread.sleep(pauseAfterSelect);
        ScreenshotReporter.takeScreenshotOrFullPage(driver, "ymm_engine_" + ymmScreenshotToken(engine));
        System.out.println("YMM: selected engine \"" + engine + "\".");
    }

    Thread.sleep(pauseAfterSelect);
    WebDriverWaits.waitForPageToLoad(driver);
    ymmClickFindButton(driver, js, wait);
}

/**
 * YMM / vehicle finder: uses MST Chosen ({@code .mst-finder__finder-filters}) when present, otherwise legacy
 * “Please select…” + Select2-style options.
 */
private static void runYmmBrandSelect(WebDriver driver, JavascriptExecutor js) throws Exception {
    FluentWait<WebDriver> wait = WebDriverWaits.waitDriver(driver, 20);
    if (isMstChosenFinderPresent(driver)) {
        System.out.println("YMM: using MST Chosen finder (.mst-finder__finder-filters).");
        runMstChosenYmmChain(driver, js, wait);
        return;
    }
    runYmmLegacySelect2Style(driver, js, wait);
}

/** Main column primary "Find" after YMM is filled. */
private static void ymmClickFindButton(WebDriver driver, JavascriptExecutor js, FluentWait<WebDriver> wait)
        throws Exception {
    List<By> findPaths = List.of(
            By.xpath("//div[@class='column main']//button[@class='action primary'][normalize-space()='Find']"),
            By.xpath("//div[contains(@class,'column') and contains(@class,'main')]"
                    + "//button[contains(@class,'action') and contains(@class,'primary')][normalize-space()='Find']"),
            By.xpath("//div[contains(@class,'main')]//button[normalize-space()='Find']")
    );
    WebElement findBtn = null;
    for (By by : findPaths) {
        try {
            findBtn = wait.until(ExpectedConditions.elementToBeClickable(by));
            if (findBtn != null) break;
        } catch (Exception ignored) { }
    }
    if (findBtn == null) {
        for (By by : findPaths) {
            for (WebElement b : driver.findElements(by)) {
                try {
                    if (b.isDisplayed()) {
                        findBtn = b;
                        break;
                    }
                } catch (Exception ignored) { }
            }
            if (findBtn != null) break;
        }
    }
    if (findBtn == null) {
        throw new NoSuchElementException(
                "YMM: Find button not found. Expected //div[@class=\"column main\"]//button[...=\"Find\"].");
    }
    js.executeScript("arguments[0].scrollIntoView({block:'center'});", findBtn);
    Thread.sleep(180);
    try {
        findBtn.click();
    } catch (Exception e) {
        js.executeScript("arguments[0].click();", findBtn);
    }
    Thread.sleep(ShopdapConfig.getInt("ymmAfterFindClickMs", 600));

    ScreenshotReporter.takeScreenshotOrFullPage(driver, "ymm_after_find");
    System.out.println("YMM: clicked Find.");
}
private static void ymmClickOptionMaybeOpenPleaseSelect(WebDriver driver, JavascriptExecutor js,
                                                        FluentWait<WebDriver> wait, String label, String notFoundMessage) throws Exception {
    WebElement el = waitForVisibleYmmOption(driver, label, Duration.ofSeconds(8));
    if (el == null) {
        WebElement dd = findFirstVisiblePleaseSelect(driver, wait);
        if (dd != null) {
            js.executeScript("arguments[0].scrollIntoView({block:'center'});", dd);
            Thread.sleep(200);
            try {
                dd.click();
            } catch (Exception e) {
                js.executeScript("arguments[0].click();", dd);
            }
            Thread.sleep(280);
            el = waitForVisibleYmmOption(driver, label, Duration.ofSeconds(15));
        }
    }
    if (el == null) {
        throw new NoSuchElementException(notFoundMessage);
    }
    js.executeScript("arguments[0].scrollIntoView({block:'center'});", el);
    Thread.sleep(120);
    try {
        el.click();
    } catch (Exception e) {
        js.executeScript("arguments[0].click();", el);
    }
}

/** Safe fragment for screenshot filenames (Windows + readable). */
private static String ymmScreenshotToken(String s) {
    if (s == null || s.isBlank()) return "na";
    return s.replaceAll("[\\\\/:*?\"<>|\\s]+", "_").replaceAll("[()]", "_").replaceAll("_+", "_");
}

/** Poll until a clickable YMM option appears (span/div/li/TestRigor paths, etc.). */
private static WebElement waitForVisibleYmmOption(WebDriver driver, String label, Duration timeout) {
    String lit = xpathLiteral(label);
    List<By> candidates = List.of(
            By.xpath("//span[normalize-space()=" + lit + "]"),
            By.xpath("//div[normalize-space()=" + lit + "]"),
            By.xpath("//li[normalize-space()=" + lit + "]"),
            By.xpath("//a[normalize-space()=" + lit + "]"),
            By.xpath("//button[normalize-space()=" + lit + "]"),
            By.xpath("//*[@role='option' and normalize-space()=" + lit + "]"),
            By.xpath("//li[contains(@class,'select2-results__option') and normalize-space()=" + lit + "]"),
            By.xpath("//*[contains(@class,'ymm') or contains(@class,'YMM')]//*[normalize-space()=" + lit + "]")
    );
    long end = System.currentTimeMillis() + timeout.toMillis();
    while (System.currentTimeMillis() < end) {
        for (By by : candidates) {
            for (WebElement el : driver.findElements(by)) {
                try {
                    if (el.isDisplayed()) return el;
                } catch (Exception ignored) { }
            }
        }
        try {
            Thread.sleep(120);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            break;
        }
    }
    return null;
}
}
