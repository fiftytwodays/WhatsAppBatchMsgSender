package in.fiftytwodays.crm;

import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.Keys;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Application to send messages to multiple people using WhatsApp Web.
 * Attachments (images, videos and other documents) can also be sent.
 */
public class WhatsAppBatchSender {

    private static final Logger LOG = System.getLogger(WhatsAppBatchSender.class.getName());

    private static final Duration QR_SCAN_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration ELEMENT_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration SHORT_PAUSE = Duration.ofMillis(500);
    private static final Duration MESSAGE_SEND_PAUSE = Duration.ofSeconds(5);
    private static final Duration ATTACHMENT_UPLOAD_PAUSE = Duration.ofSeconds(5);
    private static final Duration FINAL_SEND_PAUSE = Duration.ofSeconds(30);
    private static final Duration BROWSER_CLOSE_PAUSE = Duration.ofSeconds(5);

    private static final Set<String> IMAGE_EXTENSIONS = Set.of("png", "jpg", "jpeg", "gif", "bmp", "webp");
    private static final Set<String> VIDEO_EXTENSIONS = Set.of("mp4", "avi", "mov", "wmv", "flv", "webm");

    // Plain string sorting would put "10" before "2"; this compares embedded digit
    // runs numerically instead, so "1, 2, ..., 10" sorts in the order files were named.
    private static final Comparator<String> NATURAL_ORDER = WhatsAppBatchSender::compareNaturally;

    // Locators are centralised here so a WhatsApp Web UI change only needs updating in one place.
    // Attach includes fallbacks because WhatsApp rolls DOM changes out gradually per account.
    private static final By CHAT_LIST_PANE = By.xpath("//div[@id='pane-side']");
    private static final By CONTINUE_BANNER_BUTTON = By.xpath("//button[.//span[contains(text(),'Continue')]]");
    private static final By MESSAGE_BOX = By.xpath("//div[@contenteditable='true'][contains(@aria-placeholder,'message')]");
    private static final By ATTACH_BUTTON = By.xpath(
            "//button[.//span[@data-icon='plus-rounded' or @data-icon='clip' or @data-icon='attach-menu-plus']]"
                    + " | //button[contains(@aria-label,'Attach')]");
    private static final By SEND_BUTTON = By.xpath("//*[self::div or self::button][contains(@aria-label,'Send')]");
    private static final By MEDIA_MENU_ITEM = By.xpath("//button[@aria-label='Photos & videos']");
    private static final By DOCUMENT_MENU_ITEM = By.xpath("//button[@aria-label='Document']");

    public static void main(String[] args) {
        Properties properties = loadProperties(System.getenv("properties"));
        List<Contact> contacts = loadContacts(properties.getProperty("contacts"));
        String message = loadMessage(properties.getProperty("message-file"));
        List<String> attachments = loadAttachments(properties.getProperty("attachments-path"));

        System.setProperty("webdriver.chrome.driver", properties.getProperty("chrome-webdriver-path"));
        var options = new ChromeOptions();
        options.addArguments("--remote-allow-origins=*");
        WebDriver driver = new ChromeDriver(options);

        try {
            openWhatsAppWeb(driver);

            for (Contact contact : contacts) {
                try {
                    openChatFor(driver, contact);
                    sendMessage(driver, formatMessage(message, contact), contact);
                    sendAttachments(driver, attachments);
                } catch (Exception ex) {
                    LOG.log(Level.ERROR, "Failed sending message to " + contact.getName(), ex);
                }
            }
            sleep(FINAL_SEND_PAUSE);
        } finally {
            sleep(BROWSER_CLOSE_PAUSE);
            driver.quit();
        }
    }

    private static void openWhatsAppWeb(WebDriver driver) {
        driver.get("https://web.whatsapp.com");

        new WebDriverWait(driver, QR_SCAN_TIMEOUT)
                .until(ExpectedConditions.presenceOfElementLocated(CHAT_LIST_PANE));
        LOG.log(Level.INFO, "WhatsApp Web is ready. Proceeding with automation...");

        dismissUpdateBannerIfPresent(driver);
    }

    /**
     * Opens the chat for this contact by navigating straight to WhatsApp's own
     * "click to chat" URL, instead of typing into search and pressing Enter. Search
     * results update asynchronously, so pressing Enter right after typing is a race
     * that can land on a stale top result - i.e. the wrong contact - intermittently.
     * This also makes an explicit "close chat" step between contacts unnecessary,
     * since each navigation replaces whatever chat was previously open.
     */
    private static void openChatFor(WebDriver driver, Contact contact) {
        String phone = URLEncoder.encode(contact.getPhoneNo(), StandardCharsets.UTF_8);
        driver.get("https://web.whatsapp.com/send?phone=" + phone);
        dismissUpdateBannerIfPresent(driver);
        waitVisible(driver, MESSAGE_BOX);
    }

    private static void dismissUpdateBannerIfPresent(WebDriver driver) {
        // findElements can match a hidden node elsewhere in the DOM that happens to
        // contain "Continue" text, so only click one that's actually displayed.
        driver.findElements(CONTINUE_BANNER_BUTTON).stream()
                .filter(WebElement::isDisplayed)
                .findFirst()
                .ifPresent(banner -> {
                    banner.click();
                    sleep(SHORT_PAUSE);
                    LOG.log(Level.DEBUG, "Dismissed update banner");
                });
    }

    private static Properties loadProperties(String propertiesPath) {
        LOG.log(Level.INFO, "Properties path set to " + propertiesPath);
        try (var reader = Files.newBufferedReader(Path.of(propertiesPath), StandardCharsets.UTF_8)) {
            var properties = new Properties();
            properties.load(reader);
            LOG.log(Level.INFO, "Properties read: " + properties);
            return properties;
        } catch (IOException e) {
            throw new UncheckedIOException("Error while reading properties file - " + e.getMessage(), e);
        }
    }

    private static List<Contact> loadContacts(String contactsPath) {
        try {
            return new ExcelReader().readExcel(contactsPath);
        } catch (IOException e) {
            throw new UncheckedIOException("Error while reading the contacts list file - " + e.getMessage(), e);
        }
    }

    private static String loadMessage(String messageFilePath) {
        try {
            return new FileReader().readFile(messageFilePath);
        } catch (IOException e) {
            throw new UncheckedIOException("Error while reading the message to send - " + e.getMessage(), e);
        }
    }

    private static List<String> loadAttachments(String attachmentsPath) {
        try {
            return getFilePaths(attachmentsPath);
        } catch (IOException e) {
            throw new UncheckedIOException("Error while reading attachments path - " + e.getMessage(), e);
        }
    }

    private static String formatMessage(String messageToSend, Contact contact) {
        return messageToSend
                .replace("${prefix}", contact.getPrefix())
                .replace("${nickname}", contact.getNickName());
    }

    private static List<String> getFilePaths(String directoryPath) throws IOException {
        var filePaths = new ArrayList<String>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(Path.of(directoryPath))) {
            for (Path entry : stream) {
                if (!Files.isDirectory(entry)) {
                    filePaths.add(entry.toAbsolutePath().toString());
                }
            }
        }
        filePaths.sort(NATURAL_ORDER);
        return filePaths;
    }

    private static void sendMessage(WebDriver driver, String messageToSend, Contact contact) {
        WebElement messageBox = waitVisible(driver, MESSAGE_BOX);
        String[] lines = messageToSend.split("\r\n");

        var actions = new Actions(driver);
        actions.moveToElement(messageBox).click();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                // Shift+Enter inserts a newline within the same WhatsApp message instead of sending it.
                actions.keyDown(Keys.SHIFT).sendKeys(Keys.ENTER).keyUp(Keys.SHIFT);
            }
            actions.sendKeys(lines[i]);
        }
        actions.perform();
        messageBox.sendKeys(Keys.ENTER);
        // Give the text message time to actually dispatch before opening the attach menu,
        // otherwise a fast-uploading image can post before it and show up out of order.
        sleep(MESSAGE_SEND_PAUSE);

        LOG.log(Level.INFO, "Message sent to " + contact.getName());
    }

    private static void sendAttachments(WebDriver driver, List<String> attachments) {
        if (attachments.isEmpty()) {
            return;
        }

        Map<Boolean, List<String>> splitFiles = attachments.stream()
                .collect(Collectors.partitioningBy(WhatsAppBatchSender::isFileImageOrVideo));

        List<String> mediaFiles = splitFiles.get(true).stream().sorted(NATURAL_ORDER).toList();
        List<String> otherFiles = splitFiles.get(false).stream().sorted(NATURAL_ORDER).toList();

        if (!mediaFiles.isEmpty()) {
            sendMediaAttachments(driver, mediaFiles);
        }
        if (!otherFiles.isEmpty()) {
            sendOtherAttachments(driver, otherFiles);
        }
        LOG.log(Level.INFO, "Attachments sent successfully!");
    }

    private static void sendMediaAttachments(WebDriver driver, List<String> files) {
        uploadFiles(driver, () -> captureFileInputTriggeredBy(driver, MEDIA_MENU_ITEM), files);
        LOG.log(Level.INFO, "Media attachments sent successfully!");
    }

    private static void sendOtherAttachments(WebDriver driver, List<String> files) {
        uploadFiles(driver, () -> captureFileInputTriggeredBy(driver, DOCUMENT_MENU_ITEM), files);
        LOG.log(Level.INFO, "Other attachments sent successfully!");
    }

    /**
     * Sends all files together when the file input accepts multiple files; otherwise
     * falls back to one attach-select-send cycle per file, since Chrome rejects a
     * multi-path sendKeys on a single-file input with "the element can not hold
     * multiple files".
     */
    private static void uploadFiles(WebDriver driver, Supplier<WebElement> fileInputSupplier, List<String> files) {
        waitClickable(driver, ATTACH_BUTTON).click();
        WebElement fileInput = fileInputSupplier.get();

        if (acceptsMultipleFiles(fileInput)) {
            fileInput.sendKeys(joinFiles(files));
            waitClickable(driver, SEND_BUTTON).click();
            sleep(ATTACHMENT_UPLOAD_PAUSE);
            return;
        }

        fileInput.sendKeys(files.get(0));
        waitClickable(driver, SEND_BUTTON).click();
        sleep(ATTACHMENT_UPLOAD_PAUSE);

        for (String file : files.subList(1, files.size())) {
            waitClickable(driver, ATTACH_BUTTON).click();
            fileInputSupplier.get().sendKeys(file);
            waitClickable(driver, SEND_BUTTON).click();
            sleep(ATTACHMENT_UPLOAD_PAUSE);
        }
    }

    /**
     * Clicks the given WhatsApp attach-menu item (e.g. "Photos & videos") but intercepts
     * the file input it targets instead of letting the OS file-picker dialog open, which
     * Selenium cannot drive. Necessary because DOM position and the input's accept
     * attribute both proved ambiguous with WhatsApp's separate "Create sticker" input.
     */
    private static WebElement captureFileInputTriggeredBy(WebDriver driver, By menuItemLocator) {
        var js = (JavascriptExecutor) driver;
        js.executeScript(
                "window.__capturedFileInput = null;"
                        + "if (!HTMLInputElement.prototype.__originalClick) {"
                        + "  HTMLInputElement.prototype.__originalClick = HTMLInputElement.prototype.click;"
                        + "}"
                        + "HTMLInputElement.prototype.click = function () {"
                        + "  if (this.type === 'file') { window.__capturedFileInput = this; return; }"
                        + "  return this.__originalClick.call(this);"
                        + "};");

        waitClickable(driver, menuItemLocator).click();

        Object captured = js.executeScript("return window.__capturedFileInput;");
        js.executeScript("HTMLInputElement.prototype.click = HTMLInputElement.prototype.__originalClick;");

        if (!(captured instanceof WebElement fileInput)) {
            throw new NoSuchElementException("WhatsApp did not open a file input for: " + menuItemLocator);
        }
        return fileInput;
    }

    private static boolean acceptsMultipleFiles(WebElement fileInput) {
        return fileInput.getDomAttribute("multiple") != null;
    }

    private static String joinFiles(List<String> files) {
        return String.join("\n", files);
    }

    private static boolean isFileImageOrVideo(String filePath) {
        int dotIndex = filePath.lastIndexOf('.');
        if (dotIndex <= 0) {
            return false;
        }
        String extension = filePath.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
        return IMAGE_EXTENSIONS.contains(extension) || VIDEO_EXTENSIONS.contains(extension);
    }

    private static int compareNaturally(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i);
            char cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int startI = i;
                int startJ = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) {
                    i++;
                }
                while (j < b.length() && Character.isDigit(b.charAt(j))) {
                    j++;
                }
                String numA = a.substring(startI, i).replaceFirst("^0+(?!$)", "");
                String numB = b.substring(startJ, j).replaceFirst("^0+(?!$)", "");
                int lengthCompare = Integer.compare(numA.length(), numB.length());
                if (lengthCompare != 0) {
                    return lengthCompare;
                }
                int digitsCompare = numA.compareTo(numB);
                if (digitsCompare != 0) {
                    return digitsCompare;
                }
            } else if (ca != cb) {
                return Character.compare(ca, cb);
            } else {
                i++;
                j++;
            }
        }
        return (a.length() - i) - (b.length() - j);
    }

    private static WebElement waitVisible(WebDriver driver, By locator) {
        return new WebDriverWait(driver, ELEMENT_TIMEOUT)
                .until(ExpectedConditions.visibilityOfElementLocated(locator));
    }

    private static WebElement waitClickable(WebDriver driver, By locator) {
        return new WebDriverWait(driver, ELEMENT_TIMEOUT)
                .until(ExpectedConditions.elementToBeClickable(locator));
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
