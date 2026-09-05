package in.fiftytwodays.crm;

import org.openqa.selenium.*;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.chrome.ChromeDriver;

import java.io.IOException;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Application to send messages to multiple people using whatsapp<br/>
 * Document can also be attached<br/>
 */
public class WhatsAppBatchSender {
    public static void main(String[] args) {

        String propertiesPath = System.getenv("properties");
        System.out.println("Properties path set to " + propertiesPath);
        Properties properties = null;
        try {
            properties = readProperties(propertiesPath);
            System.out.println("Properties Read [" + properties + "]");
        } catch (IOException e) {
            System.err.println("Error while reading properties file - " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException(e);
        }

        List<Contact> contacts = null;
        try {
            contacts = new ExcelReader().readExcel(properties.getProperty("contacts"));
        } catch (IOException e) {
            System.err.println("Error while reading the contacts list file - " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException(e);
        }

        String message = null;
        try {
            message = new FileReader().readFile(properties.getProperty("message-file"));
        } catch (IOException e) {
            System.err.println("Error while reading the message to send - " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException(e);
        }


        List<String> attachments = null;
        try {
            attachments = getFilePaths(properties.getProperty("attachments-path"));
        } catch (IOException e) {
            System.err.println("Error while reading attachments path - " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException(e);
        }

        System.setProperty("webdriver.chrome.driver", properties.getProperty("chrome-webdriver-path"));
        WebDriver driver = new ChromeDriver();
        try {
            driver.get("https://web.whatsapp.com");

            // Implicit wait to allow time for elements to load and for manual QR code scanning
            driver.manage().timeouts().implicitlyWait(5, TimeUnit.SECONDS);

            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(120));
            // Wait for the QR code to disappear and the chat list to be visible
            wait.until(ExpectedConditions.presenceOfElementLocated(By.xpath("//div[@id='pane-side']")));

            System.out.println("WhatsApp Web is ready. Proceeding with automation...");

            try {

                clickContinueOnBanner(driver);

            } catch (NoSuchElementException ex) {
                System.out.println("Banner not found");
            }


            // Wait for manual QR code scan
            for (Contact contact : contacts) {

                String formattedMessage = formatMessage(message, contact);

                try {

                    sendMessage(driver, formattedMessage, contact);

                    sendAttachments(driver, attachments);

                    closeChat(driver);

                } catch (Exception ex) {
                    ex.printStackTrace();
                    System.err.println("Failed sending message to " + contact.getName());
                }
            }
            sleep(30000);
        } catch (Exception e) {
            System.err.println("Error while sending message via WhatsApp - " + e.getMessage());
            e.printStackTrace();
        } finally {
            // Close the browser after a delay
            sleep(5000); // Adjust this delay as needed
            driver.quit();
        }
    }

    private static void clickContinueOnBanner(WebDriver driver) {
        WebElement continueButton = driver.findElement(By.xpath("//button[.//span[text()='Continue']]"));
        continueButton.click();
        sleep(500);
    }

    private static String formatMessage(String messageToSend, Contact contact) {
        messageToSend = messageToSend.replace("${prefix}", contact.getPrefix());
        messageToSend = messageToSend.replace("${nickname}", contact.getNickName());
        return messageToSend;
    }

    private static List<String> getFilePaths(String directoryPath) throws IOException {
        List<String> filePaths = new ArrayList<>();

        // Convert the string path to a Path object
        Path path = Paths.get(directoryPath);

        // Use a try-with-resources statement to ensure the directory stream is closed properly
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
            for (Path entry : stream) {
                // Check if the entry is not a directory
                if (!Files.isDirectory(entry)) {
                    // Add the full path of the file to the list
                    filePaths.add(entry.toAbsolutePath().toString());
                }
            }
        }
        Collections.sort(filePaths);
        return filePaths;
    }

    private static Properties readProperties(String propertiesPath) throws IOException {
        java.io.FileReader reader = new java.io.FileReader(propertiesPath);
        Properties properties = new Properties();
        properties.load(reader);
        return properties;
    }

    private static void sendMessage(WebDriver driver, String messageToSend, Contact contact) {

        // Search for the contact/group
        WebElement searchBox = driver.findElement(By.xpath("//input[@aria-label='Search or start a new chat']"));

        searchBox.click();
        searchBox.sendKeys(Keys.CONTROL + "a");  // Select all text
        searchBox.sendKeys(Keys.DELETE);  // Delete selected text

        searchBox = driver.findElement(By.xpath("//input[@aria-label='Search or start a new chat']"));
        searchBox.click();
        searchBox.sendKeys(contact.getPhoneNo());
        sleep(2000); // Wait for search results to appear
        searchBox.sendKeys(Keys.ENTER);

        // Find the message input box
        WebElement messageBox = driver.findElement(By.xpath("//div[@aria-placeholder='Type a message']"));

        // Split the message by new lines
        String[] lines = messageToSend.split("\r\n");

        Actions actions = new Actions(driver);
        actions.moveToElement(messageBox).click();

        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                // This simulates pressing Shift+Enter to create a new line in the same message
                actions.keyDown(Keys.SHIFT).sendKeys(Keys.ENTER).keyUp(Keys.SHIFT);
            }
            // Type the line of text
            actions.sendKeys(lines[i]);
        }

        // Finally, perform all the actions
        actions.perform();
        messageBox.sendKeys(Keys.ENTER);

        System.out.println("Message sent successfully!");
    }

    private static void sendAttachments(WebDriver driver, List<String> attachments) {

        if (!attachments.isEmpty()) {

            // Split the list into two based on isFileImageOrVideo method
            Map<Boolean, List<String>> splitFiles = attachments.stream()
                    .collect(Collectors.partitioningBy(WhatsAppBatchSender::isFileImageOrVideo));

            // Extract the lists
            List<String> mediaFiles = splitFiles.get(true);
            Collections.sort(mediaFiles);
            System.out.println(mediaFiles);
            List<String> otherFiles = splitFiles.get(false);
            Collections.sort(otherFiles);

            if (!mediaFiles.isEmpty()) {
                sendMediaAttachments(driver, mediaFiles);
            }
            if (!otherFiles.isEmpty()) {
                sendOtherAttachments(driver, otherFiles);
            }
        }
        System.out.println("Attachments sent successfully!");
    }

    private static void closeChat(WebDriver driver) {
        // //span[@data-icon='menu']
        WebElement menuButton = driver.findElement(By.xpath("//div[@id='main']//button[@title='Menu']"));
        menuButton.click();
        sleep(500);
        WebElement closeButton = driver.findElement(By.xpath("//li[.//span[text()='Close chat']]"));
        closeButton.click();
        sleep(500);
        System.out.println("Chat closed");
    }

    private static void sendMediaAttachments(WebDriver driver, List<String> files) {
        clickAttachmentButton(driver);
        addMediaAttachment(driver, joinFile(files));
        clickSendButton(driver);
        sleep(5000);
        System.out.println("Media attachments sent successfully!");
    }

    private static String joinFile(List<String> files) {
        return String.join("\n", files);
    }

    private static void sendOtherAttachments(WebDriver driver, List<String> files) {
        clickAttachmentButton(driver);
        addOtherAttachment(driver, joinFile(files)); // Sending the file path directly to the input element
        clickSendButton(driver);
        sleep(5000);
        System.out.println("Other attachments sent successfully!");
    }

    private static void clickAttachmentButton(WebDriver driver) {
        // Click the attachment clip
        WebElement attachmentBtn = driver.findElement(By.xpath("//button[@aria-label='Attach']"));
        attachmentBtn.click();
    }

    private static void clickSendButton(WebDriver driver) {
        // Click the send button for the attachment
        WebElement sendButton = driver.findElement(By.xpath("//div[contains(@aria-label, 'Send')]"));
        sendButton.click();
    }

    private static void addMediaAttachment(WebDriver driver, String filePath) {
        WebElement inputFile = driver.findElement(By.xpath("//input[@accept='image/*,video/mp4,video/3gpp,video/quicktime']"));
//        WebElement inputFile = driver.findElement(By.xpath("//button[contains(@aria-label, 'Photos & videos')]"));
//        inputFile.click();
//        sleep(5000);
        inputFile.sendKeys(filePath);
    }

    private static void addOtherAttachment(WebDriver driver, String filePath) {
        WebElement inputFile = driver.findElement(By.xpath("//input[@accept='*']"));
        System.out.println("File Path: " + filePath);
        inputFile.sendKeys(filePath); // Sending the file path directly to the input element
    }

    private static boolean isFileImageOrVideo(String filePath) {
        String extension = "";
        int i = filePath.lastIndexOf('.');
        if (i > 0) {
            extension = filePath.substring(i+1).toLowerCase();
        } else {
            return false;
        }
        // List of common image and video file extensions
        List<String> imageExtensions = Arrays.asList("png", "jpg", "jpeg", "gif", "bmp");
        List<String> videoExtensions = Arrays.asList("mp4", "avi", "mov", "wmv", "flv");
        List<String> combinedExtensions = new ArrayList<>();
        combinedExtensions.addAll(imageExtensions);
        combinedExtensions.addAll(videoExtensions);
        return combinedExtensions.contains(extension);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}
