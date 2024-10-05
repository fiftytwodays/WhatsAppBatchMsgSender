package in.fiftytwodays.crm;

import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;

import java.io.IOException;
import org.openqa.selenium.interactions.Actions;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.TimeUnit;

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
            driver.manage().timeouts().implicitlyWait(30, TimeUnit.SECONDS);

            // Wait for manual QR code scan
            sleep(10000); // Adjust this value based on how quickly you can scan the QR code

            for (Contact contact : contacts) {

                String formattedMessage = formatMessage(message, contact);

                sendMessage(driver, formattedMessage, contact);

                attachments.forEach(filePath -> sendAttachments(driver, filePath));
            }
        } catch (Exception e) {
            System.err.println("Error while sending message via WhatsApp - " + e.getMessage());
            e.printStackTrace();
        } finally {
            // Close the browser after a delay
            sleep(5000); // Adjust this delay as needed
            driver.quit();
        }
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

        sleep(2000);

        // Search for the contact/group
//        WebElement searchBox = driver.findElement(By.xpath("//div[@title='Search input textbox']"));
        WebElement searchBox = driver.findElement(By.xpath("//p[contains(@class, 'selectable-text') and contains(@class, 'copyable-text')]"));

        searchBox.click();
        searchBox.sendKeys(contact.getPhoneNo());
        sleep(2000); // Wait for search results to appear
        searchBox.sendKeys(Keys.ENTER);

        // Find the message input box
//        WebElement messageBox = driver.findElement(By.xpath("//div[@title='Type a message']"));
        WebElement messageBox = driver.findElement(By.xpath("//div[@aria-placeholder='Type a message']"));

//        messageBox.sendKeys(messageToSend);
        
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

    private static void sendAttachments(WebDriver driver, String filePath) {
        // Click the attachment clip
        WebElement attachmentBtn = driver.findElement(By.xpath("//div[@title='Attach']"));
        attachmentBtn.click();

        sleep(2000);
        WebElement inputFile = null;
        if (isFileImageOrVideo(filePath)) {
            inputFile = driver.findElement(By.xpath("//input[@accept='image/*,video/mp4,video/3gpp,video/quicktime']"));
        } else {
            inputFile = driver.findElement(By.xpath("//input[@accept='*']"));
        }
        inputFile.sendKeys(filePath); // Sending the file path directly to the input element

        // Wait for upload
        sleep(2000);

        // Click the send button for the attachment
        WebElement sendButton = driver.findElement(By.xpath("//span[@data-icon='send']"));
        sendButton.click();

        sleep(5000);

        System.out.println("Attachment sent successfully!");
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
