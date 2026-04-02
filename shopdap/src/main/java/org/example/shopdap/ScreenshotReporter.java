package org.example.shopdap;

import org.openqa.selenium.Dimension;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chromium.ChromiumDriver;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** PNG screenshots, ImgBB upload, CSV append, HTML TestReport. */
public final class ScreenshotReporter {
    private ScreenshotReporter() {}

    static int totalSteps;
    static int passedSteps;
    static int failedSteps;
    static final String startTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
    static final List<String> htmlSteps = new ArrayList<>();
    static boolean imgbbDelayBeforeNextUpload;
    static boolean imgbbRateLimitedThisRun;

/** Shortcut: records step as pass with a generic detail message. */
public static void takeScreenshot(WebDriver driver, String title) throws IOException {
    takeScreenshot(driver, title, true, "Step completed successfully");
}

/**
 * Saves PNG under {@link ShopdapConfig#screenshotsDir}, optional ImgBB upload, CSV append, and full HTML report rewrite.
 */
public static void takeScreenshot(WebDriver driver, String title, boolean isPass, String details) throws IOException {
    File src = ((TakesScreenshot) driver).getScreenshotAs(OutputType.FILE);
    byte[] png = Files.readAllBytes(src.toPath());
    persistAndReportPng(title, isPass, details + " | viewport", png);
}

/** Writes PNG bytes, ImgBB, CSV, and HTML step (increments step counters). */
private static void persistAndReportPng(String title, boolean isPass, String details, byte[] png) throws IOException {
    totalSteps++;
    if (isPass) {
        passedSteps++;
    } else {
        failedSteps++;
    }

    String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
    String fileName = (isPass ? "SUCCESS_" : "ERROR_") + title + "_" + timestamp + ".png";

    File folder = new File(ShopdapConfig.screenshotsDir);
    if (!folder.exists()) {
        folder.mkdirs();
    }

    File outputFile = new File(folder, fileName);
    Files.write(outputFile.toPath(), png);
    System.out.println("Screenshot saved: " + outputFile.getAbsolutePath());

    String uploadedUrl;
    if (!ShopdapConfig.imgbbUploadEnabled) {
        uploadedUrl = "Skipped (imgbbUpload=false in config)";
        System.out.println("ImgBB upload disabled in config.");
    } else if (ShopdapConfig.imgbbApiKey == null || ShopdapConfig.imgbbApiKey.isBlank()) {
        uploadedUrl = "Skipped (no imgbbApiKey in config.properties)";
        System.out.println("ImgBB skipped: add imgbbApiKey in config.properties");
    } else if (imgbbRateLimitedThisRun) {
        uploadedUrl = "Skipped (ImgBB rate limit — rest of run uses local PNG only)";
        System.out.println("ImgBB: skipping upload (rate limited earlier this run).");
    } else {
        if (imgbbDelayBeforeNextUpload && ShopdapConfig.imgbbDelayMs > 0) {
            try {
                Thread.sleep(ShopdapConfig.imgbbDelayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        try {
            uploadedUrl = uploadToImgbbMultipart(outputFile);
            System.out.println("Uploaded URL: " + uploadedUrl);
        } catch (ImgbbRateLimitedException e) {
            imgbbRateLimitedThisRun = true;
            uploadedUrl = "Skipped (ImgBB rate limit — further uploads skipped this run)";
            System.out.println("ImgBB rate limit reached. Remaining screenshots: local file only until next run.");
        } catch (Exception e) {
            uploadedUrl = "Upload error: " + e.getMessage();
            System.out.println("ImgBB: " + e.getMessage());
        }
        imgbbDelayBeforeNextUpload = true;
    }

    writeCsv(timestamp, title, uploadedUrl, fileName);
    writeHtmlReport(timestamp, title, fileName, uploadedUrl, isPass, details);
}

/**
 * Chrome/Edge: {@code Page.captureScreenshot} with {@code captureBeyondViewport:true} (true full document, not window size).
 *
 * @return PNG bytes or {@code null} if disabled, not Chromium, or CDP fails
 */
private static byte[] tryChromiumDevToolsFullPagePng(WebDriver driver) {
    if (!ShopdapConfig.get("fullPageScreenshotUseCdp", "true").equalsIgnoreCase("true")) {
        return null;
    }
    if (!(driver instanceof ChromiumDriver)) {
        return null;
    }
    try {
        ChromiumDriver chromium = (ChromiumDriver) driver;
        Map<String, Object> params = new HashMap<>();
        params.put("format", "png");
        params.put("captureBeyondViewport", true);
        params.put("fromSurface", true);
        Map<String, Object> result = chromium.executeCdpCommand("Page.captureScreenshot", params);
        Object data = result != null ? result.get("data") : null;
        if (data == null) {
            return null;
        }
        return Base64.getDecoder().decode(String.valueOf(data));
    } catch (Exception e) {
        System.out.println("CDP full-page screenshot failed, will try window resize if needed: " + e.getMessage());
        return null;
    }
}

/**
 * When {@code fullPageScreenshotsEnabled} is true (default), captures the full scrollable document via
 * {@link #takeFullPageScreenshot}; otherwise viewport-only {@link #takeScreenshot}.
 */
public static void takeScreenshotOrFullPage(WebDriver driver, String title) throws IOException {
    takeScreenshotOrFullPage(driver, title, true, "Step completed successfully");
}

/** @see #takeScreenshotOrFullPage(WebDriver, String) */
public static void takeScreenshotOrFullPage(WebDriver driver, String title, boolean isPass, String details)
        throws IOException {
    if (ShopdapConfig.get("fullPageScreenshotsEnabled", "true").equalsIgnoreCase("true")) {
        takeFullPageScreenshot(driver, title, isPass, details);
    } else {
        takeScreenshot(driver, title, isPass, details);
    }
}

private static long measureFullPageWidth(JavascriptExecutor js) {
    return ((Number) js.executeScript(
            "var d=document.documentElement,b=document.body;"
                    + "return Math.max(d.scrollWidth,d.offsetWidth,b.scrollWidth,b.offsetWidth);")).longValue();
}

private static long measureFullPageHeight(JavascriptExecutor js) {
    return ((Number) js.executeScript(
            "var d=document.documentElement,b=document.body;"
                    + "return Math.max(d.scrollHeight,d.offsetHeight,b.scrollHeight,b.offsetHeight);")).longValue();
}

/**
 * Scrolls the full document so height/layout stabilizes (lazy regions, expanding cart blocks), then returns to top.
 */
private static void scrollStabilizeFullPage(JavascriptExecutor js) throws InterruptedException {
    if (!ShopdapConfig.get("fullPageScreenshotScrollStabilize", "true").equalsIgnoreCase("true")) {
        return;
    }
    int step = Math.max(200, ShopdapConfig.getInt("fullPageScreenshotScrollStepPx", 550));
    int pause = Math.max(30, ShopdapConfig.getInt("fullPageScreenshotScrollPauseMs", 100));
    long docH = measureFullPageHeight(js);
    for (long y = 0; y < docH; y += step) {
        js.executeScript("window.scrollTo(0, arguments[0]);", y);
        Thread.sleep(pause);
    }
    js.executeScript(
            "window.scrollTo(0, Math.max(document.documentElement.scrollHeight,"
                    + "document.body.scrollHeight) - 1);");
    Thread.sleep(pause + 80);
    js.executeScript("window.scrollTo(0, 0);");
    Thread.sleep(ShopdapConfig.getInt("fullPageScreenshotAfterScrollTopMs", 350));
}

/**
 * Full-page capture: prefers Chrome/Edge CDP {@code Page.captureScreenshot} with {@code captureBeyondViewport} (same idea as
 * legacy Oldcheavytruck). Otherwise resizes the window to document size (capped) and uses {@link TakesScreenshot}.
 * Restores maximize after.
 */
public static void takeFullPageScreenshot(WebDriver driver, String title, boolean isPass, String details) throws IOException {
    try {
        JavascriptExecutor js = (JavascriptExecutor) driver;
        js.executeScript("window.scrollTo(0, 0);");
        Thread.sleep(200);
        scrollStabilizeFullPage(js);
        js.executeScript("window.scrollTo(0, 0);");
        Thread.sleep(150);

        byte[] cdpPng = tryChromiumDevToolsFullPagePng(driver);
        if (cdpPng != null && cdpPng.length > 0) {
            persistAndReportPng(title, isPass, details + " | full page (Chrome/Edge CDP captureBeyondViewport)", cdpPng);
            return;
        }

        long w = measureFullPageWidth(js);
        long h = measureFullPageHeight(js);
        int capW = ShopdapConfig.getInt("fullPageScreenshotMaxWidth", 3840);
        int capH = ShopdapConfig.getInt("fullPageScreenshotMaxHeight", 20000);
        int nw = (int) Math.min(Math.max(w, 800), capW);
        int nh = (int) Math.min(Math.max(h, 600), capH);
        int settleMs = ShopdapConfig.getInt("fullPageScreenshotSettleMs", 450);
        driver.manage().window().setSize(new Dimension(nw, nh));
        Thread.sleep(settleMs);
        long hAfter = measureFullPageHeight(js);
        if (hAfter > nh) {
            nh = (int) Math.min(Math.max(hAfter, 600), capH);
            driver.manage().window().setSize(new Dimension(nw, nh));
            Thread.sleep(settleMs);
        }
        js.executeScript("window.scrollTo(0, 0);");
        Thread.sleep(150);
        File src = ((TakesScreenshot) driver).getScreenshotAs(OutputType.FILE);
        byte[] png = Files.readAllBytes(src.toPath());
        persistAndReportPng(title, isPass, details + " | full page (window resize; CDP off or non-Chromium)", png);
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
    } finally {
        try {
            driver.manage().window().maximize();
            Thread.sleep(200);
        } catch (Exception ignored) { }
    }
}

/** POST multipart/form-data to ImgBB v1 API; response JSON must contain {@code "url"}. */
private static String uploadToImgbbMultipart(File imageFile) throws IOException {
    byte[] raw = Files.readAllBytes(imageFile.toPath());
    String boundary = "----Shopdap" + System.currentTimeMillis();
    HttpURLConnection conn = (HttpURLConnection) new URL("https://api.imgbb.com/1/upload").openConnection();
    conn.setDoOutput(true);
    conn.setRequestMethod("POST");
    conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
    try (OutputStream os = conn.getOutputStream()) {
        multipartField(os, boundary, "key", ShopdapConfig.imgbbApiKey, false);
        multipartFile(os, boundary, "image", "screenshot.png", "image/png", raw);
        os.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
    }
    int code = conn.getResponseCode();
    InputStream in = code >= 200 && code < 400 ? conn.getInputStream() : conn.getErrorStream();
    if (in == null) in = conn.getInputStream();
    StringBuilder response = new StringBuilder();
    try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
        String line;
        while ((line = br.readLine()) != null) response.append(line);
    }
    String json = response.toString();
    if (code < 200 || code >= 300) {
        if (code == 400 && (json.contains("Rate limit") || json.contains("\"code\":100"))) {
            throw new ImgbbRateLimitedException();
        }
        throw new IOException("HTTP " + code + " — " + (json.isBlank() ? "(empty)" : json));
    }
    if (json.contains("\"success\":false")) throw new IOException(json);
    int u = json.indexOf("\"url\":\"");
    if (u < 0) throw new IOException("No url in response");
    String rest = json.substring(u + 7);
    int end = rest.indexOf('"');
    if (end < 0) throw new IOException("Bad JSON");
    return rest.substring(0, end).replace("\\/", "/");
}

/** One form field in a multipart body. */
private static void multipartField(OutputStream os, String boundary, String name, String value, boolean textPlain)
        throws IOException {
    StringBuilder sb = new StringBuilder();
    sb.append("--").append(boundary).append("\r\n");
    sb.append("Content-Disposition: form-data; name=\"").append(name).append("\"\r\n");
    if (textPlain) sb.append("Content-Type: text/plain; charset=UTF-8\r\n");
    sb.append("\r\n").append(value).append("\r\n");
    os.write(sb.toString().getBytes(StandardCharsets.UTF_8));
}

/** File part (binary) in a multipart body. */
private static void multipartFile(OutputStream os, String boundary, String field, String filename,
                                  String contentType, byte[] bytes) throws IOException {
    String header = "--" + boundary + "\r\n"
            + "Content-Disposition: form-data; name=\"" + field + "\"; filename=\"" + filename + "\"\r\n"
            + "Content-Type: " + contentType + "\r\n\r\n";
    os.write(header.getBytes(StandardCharsets.UTF_8));
    os.write(bytes);
    os.write("\r\n".getBytes(StandardCharsets.UTF_8));
}

/** Append-only CSV: creates header row on first write. */
private static void writeCsv(String timestamp, String title, String url, String localFileName) {
    File fileObj = new File(ShopdapConfig.csvPath);
    if (fileObj.getParentFile() != null && !fileObj.getParentFile().exists()) {
        fileObj.getParentFile().mkdirs();
    }
    boolean fileExists = fileObj.exists();
    try (PrintWriter out = new PrintWriter(new BufferedWriter(new FileWriter(ShopdapConfig.csvPath, true)))) {
        if (!fileExists) out.println("Timestamp,Title,LocalFile,UploadedURL");
        out.println(csvEscape(timestamp) + "," + csvEscape(title) + "," + csvEscape(localFileName) + "," + csvEscape(url));
    } catch (IOException e) {
        e.printStackTrace();
    }
}

/** Full HTML document: summary cards plus each step in {@code htmlSteps}. */
private static void writeHtmlReport(String timestamp, String title, String localFileName, String url,
                                    boolean isPass, String details) {
    File htmlFolder = new File(ShopdapConfig.htmlDir);
    if (!htmlFolder.exists()) htmlFolder.mkdirs();
    String htmlFile = ShopdapConfig.htmlDir + File.separator + "TestReport.html";
    String relativeImgPath = "../../../screenshots/" + ShopdapConfig.runDate + "/" + ShopdapConfig.runTime + "/" + localFileName;

    String stepStatusClass = isPass ? "pass" : "fail";
    String stepStatusIcon = isPass ? "✅" : "❌";
    String safeDetails = escapeHtml(details);

    StringBuilder stepHtml = new StringBuilder();
    stepHtml.append("            <div class=\"test-step ").append(stepStatusClass).append("\">\n");
    stepHtml.append("                <div class=\"step-header\">\n");
    stepHtml.append("                    <span>").append(stepStatusIcon).append(" ")
            .append(escapeHtml(title.replace("_", " ").toUpperCase())).append("</span>\n");
    stepHtml.append("                    <span class=\"step-time\">")
            .append(timestamp.split("_")[1].replace("-", ":")).append("</span>\n");
    stepHtml.append("                </div>\n");
    stepHtml.append("                <div class=\"step-details\">").append(safeDetails).append("</div>\n");
    stepHtml.append("                <div style=\"margin-top: 15px;\">\n");
    stepHtml.append("                    <a href=\"").append(relativeImgPath).append("\" target=\"_blank\">\n");
    stepHtml.append("                        <img class=\"screenshot\" src=\"").append(relativeImgPath)
            .append("\" alt=\"").append(escapeHtml(title)).append("\">\n");
    stepHtml.append("                    </a>\n");
    stepHtml.append("                </div>\n");
    stepHtml.append("                <div style=\"margin-top: 10px;\">\n");
    stepHtml.append("                    <a class=\"btn\" href=\"").append(relativeImgPath)
            .append("\" target=\"_blank\">View Local</a>\n");
    if (url != null && url.startsWith("http")) {
        stepHtml.append("                    <a class=\"btn imgbb\" href=\"").append(escapeHtml(url))
                .append("\" target=\"_blank\">View ImgBB</a>\n");
    }
    stepHtml.append("                </div>\n");
    stepHtml.append("            </div>");

    htmlSteps.add(stepHtml.toString());

    try (PrintWriter out = new PrintWriter(new BufferedWriter(new FileWriter(htmlFile, false)))) {
        double passRate = totalSteps > 0 ? ((double) passedSteps / totalSteps) * 100 : 0;
        String overallStatus = failedSteps > 0 ? "FAILED" : "PASSED";
        String statusBadgeClass = failedSteps > 0 ? "status-fail" : "status-pass";

        out.println("<!DOCTYPE html>");
        out.println("<html lang=\"en\">");
        out.println("<head>");
        out.println("    <meta charset=\"UTF-8\">");
        out.println("    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">");
        out.println("    <title>Shopdap Automation - Test Report</title>");
        out.println("    <style>");
        out.println("        body { font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif; margin: 0; padding: 20px; background: linear-gradient(135deg, #f5f7fa 0%, #c3cfe2 100%); }");
        out.println("        .container { max-width: 1400px; margin: 0 auto; background: white; border-radius: 15px; box-shadow: 0 10px 30px rgba(0,0,0,0.1); padding: 40px; }");
        out.println("        .header { text-align: center; background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); color: white; padding: 40px; border-radius: 15px; margin-bottom: 40px; }");
        out.println("        .header h1 { margin: 0; font-size: 2.5em; font-weight: 300; }");
        out.println("        .summary { display: grid; grid-template-columns: repeat(auto-fit, minmax(250px, 1fr)); gap: 25px; margin-bottom: 40px; }");
        out.println("        .summary-card { background: linear-gradient(135deg, #f8f9fa 0%, #e9ecef 100%); padding: 25px; border-radius: 15px; text-align: center; border-left: 6px solid #667eea; }");
        out.println("        .summary-card h3 { margin: 0 0 15px 0; color: #333; font-size: 1.1em; }");
        out.println("        .summary-card .number { font-size: 2.5em; font-weight: bold; color: #333; }");
        out.println("        .progress-bar { width: 100%; height: 25px; background-color: #e9ecef; border-radius: 12px; overflow: hidden; margin: 15px 0; }");
        out.println("        .progress-fill { height: 100%; background: linear-gradient(90deg, #28a745, #20c997); border-radius: 12px; }");
        out.println("        .test-results { margin: 40px 0; display: flex; flex-direction: column; gap: 15px; }");
        out.println("        .test-step { margin: 15px 0; padding: 20px; border-radius: 12px; border-left: 6px solid; }");
        out.println("        .test-step.pass { background: linear-gradient(135deg, #d4edda 0%, #c3e6cb 100%); border-left-color: #28a745; }");
        out.println("        .test-step.fail { background: linear-gradient(135deg, #f8d7da 0%, #f5c6cb 100%); border-left-color: #dc3545; }");
        out.println("        .step-header { font-weight: bold; margin-bottom: 12px; font-size: 1.1em; }");
        out.println("        .step-details { font-size: 0.95em; color: #666; line-height: 1.5; }");
        out.println("        .step-time { font-size: 0.85em; color: #888; float: right; background: rgba(0,0,0,0.05); padding: 4px 8px; border-radius: 5px; }");
        out.println("        .screenshot { max-width: 300px; border-radius: 8px; margin: 15px 0; border: 2px solid #ddd; }");
        out.println("        .timestamp { text-align: center; color: #666; margin: 25px 0; }");
        out.println("        .status-badge { display: inline-block; padding: 8px 20px; border-radius: 25px; color: white; font-weight: bold; }");
        out.println("        .status-pass { background: linear-gradient(135deg, #28a745 0%, #20c997 100%); }");
        out.println("        .status-fail { background: linear-gradient(135deg, #dc3545 0%, #c82333 100%); }");
        out.println("        .btn { display: inline-block; padding: 8px 15px; background: linear-gradient(135deg, #28a745 0%, #20c997 100%); color: white; text-decoration: none; border-radius: 20px; font-weight: bold; font-size: 0.9em; margin-right: 10px; }");
        out.println("        .btn.imgbb { background: linear-gradient(135deg, #17a2b8 0%, #117a8b 100%); }");
        out.println("    </style>");
        out.println("</head>");
        out.println("<body>");
        out.println("    <div class=\"container\">");
        out.println("        <div class=\"header\">");
        out.println("            <h1>Shopdap Automation</h1>");
        out.println("            <p style=\"font-size: 1.2em; margin: 10px 0;\">Test Report</p>");
        out.println("            <div class=\"timestamp\">Generated on: " + ShopdapConfig.runDate + " at " + ShopdapConfig.runTime.replace("-", ":") + "</div>");
        out.println("        </div>");
        out.println("        <div class=\"summary\">");
        out.println("            <div class=\"summary-card\"><h3>Overall Status</h3><div class=\"status-badge " + statusBadgeClass + "\">" + overallStatus + "</div></div>");
        out.println("            <div class=\"summary-card\"><h3>Total Steps</h3><div class=\"number\">" + totalSteps + "</div></div>");
        out.println("            <div class=\"summary-card\"><h3>Passed</h3><div class=\"number\" style=\"color: #28a745;\">" + passedSteps + "</div></div>");
        out.println("            <div class=\"summary-card\"><h3>Failed</h3><div class=\"number\" style=\"color: #dc3545;\">" + failedSteps + "</div></div>");
        out.println("            <div class=\"summary-card\"><h3>Pass Rate</h3><div class=\"number\">" + String.format("%.1f", passRate) + "%</div>");
        out.println("                <div class=\"progress-bar\"><div class=\"progress-fill\" style=\"width: " + passRate + "%\"></div></div></div>");
        out.println("        </div>");
        String currentTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        out.println("        <div style=\"text-align: center; margin: 20px 0;\"><p><strong>Duration:</strong> " + ScreenshotReporter.startTime + " to " + currentTime + "</p></div>");
        out.println("        <div class=\"test-results\"><h2>Detailed Results</h2>");
        for (String step : htmlSteps) out.println(step);
        out.println("        </div>");
        out.println("        <div style=\"margin-top: 50px; padding-top: 20px; border-top: 1px solid #dee2e6; text-align: center; color: #6c757d;\">");
        out.println("            <p>Shopdap Automation</p></div></div></body></html>");
    } catch (IOException e) {
        e.printStackTrace();
    }
}
/** Wrap CSV fields that contain commas or quotes. */
private static String csvEscape(String s) {
    if (s == null) return "";
    if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
    return s;
}

private static String escapeHtml(String s) {
    if (s == null) return "";
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
}

/** Thrown when ImgBB API returns HTTP 400 rate limit (code 100). */
private static final class ImgbbRateLimitedException extends IOException {
    private static final long serialVersionUID = 1L;
}

}
