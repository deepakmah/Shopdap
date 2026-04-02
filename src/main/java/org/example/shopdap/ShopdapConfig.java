package org.example.shopdap;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Properties;

/**
 * Loads {@code config.properties} from the classpath or working directory; sets per-run output paths in {@link #loadConfig()}.
 */
public final class ShopdapConfig {

    private ShopdapConfig() {}

    public static final Properties config = new Properties();

    public static final String runDate = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
    public static final String runTime = new SimpleDateFormat("HH-mm-ss").format(new Date());

    public static String imgbbApiKey;
    public static boolean imgbbUploadEnabled = true;
    public static int imgbbDelayMs;
    public static String screenshotsDir;
    public static String htmlDir;
    public static String csvPath;

    public static void loadConfig() throws IOException {
        try (InputStream in = ShopdapConfig.class.getResourceAsStream("/config.properties")) {
            if (in != null) {
                config.load(in);
            }
        }
        if (config.isEmpty()) {
            try (InputStream in = new FileInputStream("config.properties")) {
                config.load(in);
            } catch (FileNotFoundException ignored) {
                // optional
            }
        }
        String base = get("basePath", "C:/Users/deepa/Documents/Automation/Shopdap").replace("\\", "/");
        imgbbApiKey = get("imgbbApiKey", "");
        imgbbUploadEnabled = get("imgbbUpload", "true").equalsIgnoreCase("true");
        imgbbDelayMs = getInt("imgbbDelayMs", 1200);
        screenshotsDir = base + "/screenshots/" + runDate + "/" + runTime;
        htmlDir = base + "/html/" + runDate + "/" + runTime;
        csvPath = get("csvPath", base + "/Shopdap.csv").replace("\\", "/");
    }

    public static String get(String key, String def) {
        String v = config.getProperty(key);
        return v != null ? v.trim() : def;
    }

    public static int getInt(String key, int def) {
        String v = config.getProperty(key);
        if (v == null) {
            return def;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
