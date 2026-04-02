/**
 * Shopdap Selenium automation split by concern. The {@code Shopdap} class in the default package holds {@code main}
 * or call these types directly:
 * <ul>
 *   <li>{@link ShopdapConfig} — {@code config.properties}, paths, typed getters</li>
 *   <li>{@link WebDriverWaits} — fluent waits and page-load</li>
 *   <li>{@link ScreenshotReporter} — PNG, ImgBB, CSV, HTML report</li>
 *   <li>{@link GeoCookieHomepageFlow} — geo modal, cookies, homepage shot, then YMM</li>
 *   <li>{@link YmmVehicleFinder} — MST Chosen or legacy YMM + Find</li>
 *   <li>{@link FinderListingFlow} — random category tile + listing verification</li>
 *   <li>{@link ProductCartFlow} — PDP swatches, add to cart, modal, minicart, cart page</li>
 * </ul>
 */
package org.example.shopdap;
