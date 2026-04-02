import org.example.shopdap.CheckoutFlow;
import org.example.shopdap.CustomerLoginFlow;
import org.example.shopdap.FinderListingFlow;
import org.example.shopdap.GeoCookieHomepageFlow;
import org.example.shopdap.ProductCartFlow;
import org.example.shopdap.ShopdapConfig;
import org.example.shopdap.WebDriverWaits;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;

/**
 * Entry point for shopdap.com automation. Implementation lives under {@code org.example.shopdap}:
 * {@link GeoCookieHomepageFlow}, {@link FinderListingFlow}, {@link ProductCartFlow}, {@link CheckoutFlow},
 * {@link CustomerLoginFlow} (after checkout when enabled),
 * {@link ShopdapConfig},
 * {@link org.example.shopdap.ScreenshotReporter}, {@link org.example.shopdap.YmmVehicleFinder}, {@link WebDriverWaits}.
 */
public class Shopdap {

    public static void main(String[] args) throws Exception {
        ShopdapConfig.loadConfig();
        ChromeOptions options = new ChromeOptions();
        if (ShopdapConfig.get("useIncognito", "true").equalsIgnoreCase("true")) {
            options.addArguments("--incognito");
        }
        WebDriver driver = new ChromeDriver(options);
        try {
            driver.manage().window().maximize();
            driver.get(ShopdapConfig.get("baseUrl", "https://www.shopdap.com/"));
            WebDriverWaits.waitForPageToLoad(driver);
            GeoCookieHomepageFlow.run(driver);
            FinderListingFlow.clickRandomFinderCategory(driver);
            FinderListingFlow.verifyProductFinder(driver);
            ProductCartFlow.openFirstListingProductAddToCartAndCloseModal(driver);
            CheckoutFlow.run(driver);
            CustomerLoginFlow.run(driver);
        } finally {
            driver.quit();
        }
    }

    /** @see WebDriverWaits#waitForPageToLoad(WebDriver) */
    public static void waitForPageToLoad(WebDriver driver) {
        WebDriverWaits.waitForPageToLoad(driver);
    }

    /** @see ProductCartFlow#productPageUnavailableForAddToCart(WebDriver) */
    public static boolean productPageUnavailableForAddToCart(WebDriver driver) {
        return ProductCartFlow.productPageUnavailableForAddToCart(driver);
    }

    /** @see ProductCartFlow#clickFirstVisibleProductFromListing(WebDriver) */
    public static String clickFirstVisibleProductFromListing(WebDriver driver) throws Exception {
        return ProductCartFlow.clickFirstVisibleProductFromListing(driver);
    }

    /** @see ProductCartFlow#clickAddToCartOnProductPage(WebDriver) */
    public static void clickAddToCartOnProductPage(WebDriver driver) throws Exception {
        ProductCartFlow.clickAddToCartOnProductPage(driver);
    }

    /** @see ProductCartFlow#closeAddToCartSuccessModal(WebDriver) */
    public static void closeAddToCartSuccessModal(WebDriver driver) throws Exception {
        ProductCartFlow.closeAddToCartSuccessModal(driver);
    }

    /** @see ProductCartFlow#openFirstListingProductAddToCartAndCloseModal(WebDriver) */
    public static void openFirstListingProductAddToCartAndCloseModal(WebDriver driver) throws Exception {
        ProductCartFlow.openFirstListingProductAddToCartAndCloseModal(driver);
    }
}
