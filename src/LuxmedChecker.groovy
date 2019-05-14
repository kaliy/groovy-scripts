import groovyx.net.http.HttpBuilder
import io.github.bonigarcia.wdm.WebDriverManager
import org.openqa.selenium.By
import org.openqa.selenium.JavascriptExecutor
import org.openqa.selenium.Keys
import org.openqa.selenium.WebDriver
import org.openqa.selenium.chrome.ChromeDriver
import org.openqa.selenium.chrome.ChromeOptions
import org.openqa.selenium.support.ui.ExpectedConditions
import org.openqa.selenium.support.ui.WebDriverWait

import java.time.LocalDateTime
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit


import static groovyx.net.http.ContentTypes.JSON
import static org.openqa.selenium.support.ui.ExpectedConditions.numberOfElementsToBe

@Grapes([
        @Grab(group = 'io.github.bonigarcia', module = 'webdrivermanager', version = '2.2.0'),
        @Grab(group = 'org.seleniumhq.selenium', module = 'selenium-java', version = '3.11.0'),
        @Grab(group = 'com.codeborne', module = 'phantomjsdriver', version = '1.4.4'),
         @Grab(group = 'io.github.http-builder-ng', module = 'http-builder-ng-core', version = '1.0.3')
])
class LuxmedChecker {

    static configuration = new ConfigSlurper().parse(new File("config.groovy").text)

    static def check() {
        WebDriverManager.chromedriver().version("2.46").setup()
        ChromeOptions options = new ChromeOptions()
        options.addArguments("--headless")
        options.addArguments("--disable-gpu")

        WebDriver driver = new ChromeDriver(options)
        driver.manage().timeouts().implicitlyWait(5, TimeUnit.SECONDS)

        try {
            driver.get("https://portalpacjenta.luxmed.pl/PatientPortal/Account/LogOn")

            driver.findElementById("Login").sendKeys(configuration.luxmed.username)
            driver.findElementById("TempPassword").click()
            driver.findElementById("Password").sendKeys(configuration.luxmed.password)
            driver.findElementById("Password").sendKeys(Keys.RETURN)

            def type = configuration.luxmed.type
            LuxmedChecker.checksMapping[type](driver)

            def numberOfVisitsAsString = (driver.findElementByXPath("//*[@class='tableListGroup']").text =~ /(.*) dostępnych terminów/)[0][1]
            def doctorsContentElement = (driver.findElementsByXPath("//*[@class='tableList']"))
            def content = doctorsContentElement.isEmpty() ? "" : doctorsContentElement[0].text
            def numberOfVisits = "Brak" == numberOfVisitsAsString ? 0 : Integer.parseInt(numberOfVisitsAsString)
            [visits: numberOfVisits, content: content]
        } finally {
            driver.quit()
        }
    }

    static HttpBuilder httpBin = HttpBuilder.configure {
        request.uri = 'https://api.pushbullet.com/'
        request.headers['Access-Token'] = configuration.pushbullet.token
        request.contentType = JSON[0]
    }

    static def notify(visits) {
        def response = httpBin.post{
            request.uri.path = '/v2/pushes'
            request.body = ['body': "Found $visits visits", "title": "luxmed $visits visits", "type": "note"]
        }
        println response
    }

    static void main(args) {
        def scheduler = Executors.newScheduledThreadPool(1)
        def previousContent = ""
        def task = {
            try {
                def visits = check()
                println "${LocalDateTime.now()}: Found ${visits.visits} visits"
                if (visits.visits && previousContent != visits.content) {
                    notify(visits.visits)
                }
                previousContent = visits.content
            } catch (Exception e) {
                e.printStackTrace()
            }
        }
        scheduler.scheduleAtFixedRate(task, 0, 1, TimeUnit.MINUTES)
    }

    static genericChecks = { WebDriver driver ->
        driver.findElementByXPath("//a[contains(text(), 'Chcę umówić wizytę lub badanie')]").click()
        driver.findElementByXPath("//a[@datasubcategory = 'Wizyta w placówce' and @datacategory = 'Pozostałe usługi']").click()
        driver.findElementByXPath("//span[@class='caption' and text() = 'Wybierz usługę']").click()
        driver.findElementsByXPath("//ul[@id='__selectOptions']/li[contains(text(), '${configuration.luxmed.service}')]")[0].click()
        (JavascriptExecutor) driver.executeScript("arguments[0].click();", driver.findElementById("__selectOverlay"))
        try {
            new WebDriverWait(driver, 1).until(ExpectedConditions.visibilityOfElementLocated(By.id('spinnerDiv')))
            new WebDriverWait(driver, 2).until(ExpectedConditions.invisibilityOfElementLocated(By.id('spinnerDiv')))
        } catch(ignored){}
        new WebDriverWait(driver, 5).until(ExpectedConditions.elementToBeClickable(By.id("reservationSearchSubmitButton"))).click()
    }

    static orthopedistChecks = { WebDriver driver ->
        driver.findElementByXPath("//a[contains(text(), 'Chcę umówić wizytę lub badanie')]").click()
        driver.findElementByXPath("//a[@datasubcategory = 'Inny problem' and @datacategory = 'Ortopeda']").click()
        new WebDriverWait(driver, 5).until(ExpectedConditions.elementToBeClickable(By.xpath("//a[@datapagepath='/Symptoms' and text() = 'Wizyta w placówce']"))).click()
        new WebDriverWait(driver, 5).until(ExpectedConditions.elementToBeClickable(By.id("reservationSearchSubmitButton"))).click()
    }

    static slotChangingChecks = {driver ->
        driver.findElementByXPath("//a[contains(@class, 'changeTermButton') and contains(@data-service, '${configuration.luxmed.service}')]").click()
        new WebDriverWait(driver, 5)
                .until(
                        numberOfElementsToBe(
                                By.xpath("//button[@id='Popup_SearchTerms_Btn' and contains(text(), 'Szukaj')]"),
                                2
                        )
                ).last().click()
    }

    static checksMapping = [
            'slot-change': slotChangingChecks,
            'orthopedist': orthopedistChecks,
            'generic': genericChecks
    ]

}