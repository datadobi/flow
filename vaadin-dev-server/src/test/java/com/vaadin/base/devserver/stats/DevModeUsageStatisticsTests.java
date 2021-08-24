/*
 * Copyright 2000-2021 Vaadin Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.vaadin.base.devserver.stats;

import com.sun.net.httpserver.HttpServer;
import com.vaadin.flow.server.startup.ApplicationConfiguration;
import com.vaadin.flow.testutil.TestUtils;
import junit.framework.TestCase;
import net.jcip.annotations.NotThreadSafe;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.junit.*;
import org.mockito.Mockito;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

@NotThreadSafe
public class DevModeUsageStatisticsTests extends TestCase {

    private static final int HTTP_PORT = 8089;
    public static final String USAGE_REPORT_URL_LOCAL = "http://localhost:"+HTTP_PORT+"/";
    public static final String DEFAULT_SERVER_MESSAGE = "{\"reportInterval\":86400,\"serverMessage\":\"\"}";
    public static final String SERVER_MESSAGE_MESSAGE  = "{\"reportInterval\":86400,\"serverMessage\":\"Hello\"}";
    public static final String SERVER_MESSAGE_3H  = "{\"reportInterval\":10800,\"serverMessage\":\"\"}";
    public static final String SERVER_MESSAGE_48H  = "{\"reportInterval\":172800,\"serverMessage\":\"\"}";
    public static final String SERVER_MESSAGE_40D  = "{\"reportInterval\":3456000,\"serverMessage\":\"\"}";
    public static final String INVALID_SERVER_MESSAGE = "{\"reportInterval\":3days,\"serverMessage\":\"\"}";
    private static final long SEC_12H =  60*60*12;
    private static final long SEC_24H =  60*60*24;
    private static final long SEC_48H =  60*60*48;
    private static final long SEC_30D =  60*60*24*30;

    @Before
    public void setup() throws Exception {
    }


    @After
    public void teardown() throws Exception {
    }

    @Test
    public void testClientData() throws Exception {

        // Create mock app configuration
        ApplicationConfiguration configuration = mockAppConfig(true);

        // Change the file storage and reporting parameters for testing
        StatisticsStorage.get().setUsageReportingUrl(USAGE_REPORT_URL_LOCAL);
        StatisticsStorage.get().setUsageStatisticsStore(createTempStorage("stats-data/usage-statistics-1.json"));

        // Init using test project
        String mavenProjectFolder = TestUtils.getTestFolder("stats-data/maven-project-folder1").toPath().toString();
        DevModeUsageStatistics.init(configuration, mavenProjectFolder);


        // Send and see that data ws collected
        try (TestHttpServer server = new TestHttpServer(200, DEFAULT_SERVER_MESSAGE)) {
            long lastSend = StatisticsStorage.get().getLastSendTime();
            StatisticsStorage.get().sendCurrentStatistics();
            long newSend = StatisticsStorage.get().getLastSendTime();
            Assert.assertTrue("Send time should be updated",newSend > lastSend);
            Assert.assertTrue("Status should be 200", StatisticsStorage.get().getLastSendStatus().contains("200"));
            Assert.assertEquals("Default interval should be 24H in seconds", SEC_24H, StatisticsStorage.get().getInterval());
        }


    }

    @Test
    public void testMultipleProjects() throws Exception {

        // Create mock app configuration
        ApplicationConfiguration configuration = mockAppConfig(true);

        // Change the file storage and reporting parameters for testing
        StatisticsStorage.get().setUsageReportingUrl(USAGE_REPORT_URL_LOCAL);
        StatisticsStorage.get().setUsageStatisticsStore(createTempStorage("stats-data/usage-statistics-1.json"));

        // Init using test project
        String mavenProjectFolder = TestUtils.getTestFolder("stats-data/maven-project-folder1").toPath().toString();
        DevModeUsageStatistics.init(configuration, mavenProjectFolder);
        // Data contains 5 previous starts for this project
        Assert.assertEquals("Expected to have no restarts", 6, StatisticsStorage.get().getFieldValue("devModeStarts"));

        // Switch project to track
        String mavenProjectFolder2 = TestUtils.getTestFolder("stats-data/maven-project-folder2").toPath().toString();
        DevModeUsageStatistics.init(configuration, mavenProjectFolder2);
        Assert.assertEquals("Expected to have no restarts", 0, StatisticsStorage.get().getFieldValue("devModeStarts"));

        // Switch project to track
        String gradleProjectFolder1 = TestUtils.getTestFolder("stats-data/gradle-project-folder1").toPath().toString();
        DevModeUsageStatistics.init(configuration, gradleProjectFolder1);
        Assert.assertEquals("Expected to have no restarts", 0, StatisticsStorage.get().getFieldValue("devModeStarts"));

        // Switch project to track
        String gradleProjectFolder2 = TestUtils.getTestFolder("stats-data/gradle-project-folder2").toPath().toString();
        DevModeUsageStatistics.init(configuration, gradleProjectFolder2);
        DevModeUsageStatistics.init(configuration, gradleProjectFolder2); // Double init to check restart count
        Assert.assertEquals("Expected to have 1 restarts", 1, StatisticsStorage.get().getFieldValue("devModeStarts"));

        // Check that all project are stored correctly
        Assert.assertEquals("Expected to have 4 projects", 4, StatisticsStorage.get().getNumberOfProjects());

    }

    @Test
    public void testSend() throws Exception {

        // Create mock app configuration
        ApplicationConfiguration configuration = mockAppConfig(true);

        // Change the file storage and reporting parameters for testing
        StatisticsStorage.get().setUsageReportingUrl(USAGE_REPORT_URL_LOCAL);
        StatisticsStorage.get().setUsageStatisticsStore(createTempStorage("stats-data/usage-statistics-1.json"));

        // Init using test project
        String mavenProjectFolder = TestUtils.getTestFolder("stats-data/maven-project-folder1").toPath().toString();
        DevModeUsageStatistics.init(configuration, mavenProjectFolder);

        // Test with default server response
        try (TestHttpServer server = new TestHttpServer(200, DEFAULT_SERVER_MESSAGE)) {
            long lastSend = StatisticsStorage.get().getLastSendTime();
            DevModeUsageStatistics.sendCurrentStatistics();
            long newSend = StatisticsStorage.get().getLastSendTime();
            Assert.assertTrue("Send time should be updated",newSend > lastSend);
            Assert.assertTrue("Status should be 200", StatisticsStorage.get().getLastSendStatus().contains("200"));
            Assert.assertEquals("Default interval should be 24H in seconds", SEC_24H, StatisticsStorage.get().getInterval());
        }

        // Test with server response with too custom interval
        try (TestHttpServer server = new TestHttpServer(200, SERVER_MESSAGE_48H)) {
            long lastSend = StatisticsStorage.get().getLastSendTime();
            StatisticsStorage.get().sendCurrentStatistics();
            long newSend = StatisticsStorage.get().getLastSendTime();
            Assert.assertTrue("Send time should be updated",newSend > lastSend);
            Assert.assertTrue("Status should be 200", StatisticsStorage.get().getLastSendStatus().contains("200"));
            Assert.assertEquals("Custom interval should be 48H in seconds",SEC_48H, StatisticsStorage.get().getInterval());
        }

        // Test with server response with too short interval
        try (TestHttpServer server = new TestHttpServer(200, SERVER_MESSAGE_3H)) {
            long lastSend = StatisticsStorage.get().getLastSendTime();
            StatisticsStorage.get().sendCurrentStatistics();
            long newSend = StatisticsStorage.get().getLastSendTime();
            Assert.assertTrue("Send time should be updated",newSend > lastSend);
            Assert.assertTrue("Status should be 200", StatisticsStorage.get().getLastSendStatus().contains("200"));
            Assert.assertEquals("Minimum interval should be 12H in seconds",SEC_12H, StatisticsStorage.get().getInterval());
        }

        // Test with server response with too long interval
        try (TestHttpServer server = new TestHttpServer(200, SERVER_MESSAGE_40D)) {
            long lastSend = StatisticsStorage.get().getLastSendTime();
            StatisticsStorage.get().sendCurrentStatistics();
            long newSend = StatisticsStorage.get().getLastSendTime();
            Assert.assertTrue("Send time should be not be updated",newSend > lastSend);
            Assert.assertTrue("Status should be 200", StatisticsStorage.get().getLastSendStatus().contains("200"));
            Assert.assertEquals("Maximum interval should be 30D in seconds",SEC_30D, StatisticsStorage.get().getInterval());
        }


        // Test with server fail response
        try (TestHttpServer server = new TestHttpServer(500, SERVER_MESSAGE_40D)) {
            long lastSend = StatisticsStorage.get().getLastSendTime();
            StatisticsStorage.get().sendCurrentStatistics();
            long newSend = StatisticsStorage.get().getLastSendTime();
            Assert.assertTrue("Send time should be updated",newSend > lastSend);
            Assert.assertTrue("Status should be 500", StatisticsStorage.get().getLastSendStatus().contains("500"));
            Assert.assertEquals("In case of errors we should use default interval",SEC_24H, StatisticsStorage.get().getInterval());
        }

        // Test with server returned message
        try (TestHttpServer server = new TestHttpServer(200, SERVER_MESSAGE_MESSAGE)) {
            long lastSend = StatisticsStorage.get().getLastSendTime();
            StatisticsStorage.get().sendCurrentStatistics();
            long newSend = StatisticsStorage.get().getLastSendTime();
            Assert.assertTrue("Send time should be updated",newSend > lastSend);
            Assert.assertTrue("Status should be 200", StatisticsStorage.get().getLastSendStatus().contains("200"));
            Assert.assertEquals("Default interval should be 24H in seconds", SEC_24H, StatisticsStorage.get().getInterval());
            Assert.assertEquals("Message should be returned", "Hello", StatisticsStorage.get().getLastServerMessage());
        }

        // Test with invalid material
        StatisticsStorage.get().setUsageStatisticsStore(new File(TestUtils.getTestResource("stats-data/usage-statistics-2.json").getFile()));
        //TODO:
    }

    @Test
    public void testMavenProjectProjectId() {
        String mavenProjectFolder1 = TestUtils.getTestFolder("stats-data/maven-project-folder1").toPath().toString();
        String mavenProjectFolder2 = TestUtils.getTestFolder("stats-data/maven-project-folder2").toPath().toString();
        String id1 = ProjectHelpers.generateProjectId(mavenProjectFolder1);
        String id2 = ProjectHelpers.generateProjectId(mavenProjectFolder2);
        Assert.assertNotNull(id1);
        Assert.assertNotNull(id2);
        Assert.assertNotEquals(id1,id2); // Should differ
    }

    @Test
    public void testMavenProjectSource() {
        String mavenProjectFolder1 = TestUtils.getTestFolder("stats-data/maven-project-folder1").toPath().toString();
        String mavenProjectFolder2 = TestUtils.getTestFolder("stats-data/maven-project-folder2").toPath().toString();
        String source1 = ProjectHelpers.getProjectSource(mavenProjectFolder1);
        String source2 = ProjectHelpers.getProjectSource(mavenProjectFolder2);
        Assert.assertEquals("https://start.vaadin.com/test/1",source1);
        Assert.assertEquals("https://start.vaadin.com/test/2",source2);
    }

    @Test
    public void testGradleProjectProjectId() {
        String gradleProjectFolder1 = TestUtils.getTestFolder("stats-data/gradle-project-folder1").toPath().toString();
        String gradleProjectFolder2 = TestUtils.getTestFolder("stats-data/gradle-project-folder2").toPath().toString();
        String id1 = ProjectHelpers.generateProjectId(gradleProjectFolder1);
        String id2 = ProjectHelpers.generateProjectId(gradleProjectFolder2);
        Assert.assertNotNull(id1);
        Assert.assertNotNull(id2);
        Assert.assertNotEquals(id1,id2); // Should differ
    }

    @Test
    public void testGradleProjectSource() {
        String gradleProjectFolder1 = TestUtils.getTestFolder("stats-data/gradle-project-folder1").toPath().toString();
        String gradleProjectFolder2 = TestUtils.getTestFolder("stats-data/gradle-project-folder2").toPath().toString();
        String source1 = ProjectHelpers.getProjectSource(gradleProjectFolder1);
        String source2 = ProjectHelpers.getProjectSource(gradleProjectFolder2);
        Assert.assertEquals("https://start.vaadin.com/test/3",source1);
        Assert.assertEquals("https://start.vaadin.com/test/4",source2);
    }

    @Test
    public void testMissingProject() {
        String mavenProjectFolder1 = TestUtils.getTestFolder("java").toPath().toString();
        String mavenProjectFolder2 = TestUtils.getTestFolder("stats-data/empty").toPath().toString();
        String id1 = ProjectHelpers.generateProjectId(mavenProjectFolder1);
        String id2 = ProjectHelpers.generateProjectId(mavenProjectFolder2);
        Assert.assertNotNull(id1);
        Assert.assertNotNull(id2);
        Assert.assertEquals(id1,id2); // Should be the default id in both cases
    }

    @Test
    public void testReadUserKey() throws IOException {
        ApplicationConfiguration configuration = mockAppConfig(true);
        String mavenProjectFolder = TestUtils.getTestFolder("stats-data/maven-project-folder1").toPath().toString();
        System.setProperty("user.home", TestUtils.getTestFolder("stats-data").toPath().toString()); //Change the home location
        DevModeUsageStatistics.init(configuration, mavenProjectFolder);

        // Read from file
        String keyString = "user-ab641d2c-test-test-file-223cf1fa628e";
        String key = ProjectHelpers.getUserKey();
        assertEquals(keyString,key);

        // Try with non existent
        File tempDir = File.createTempFile("user.home","test");
        tempDir.delete(); // Delete
        tempDir.mkdir(); // Recreate as directory
        tempDir.deleteOnExit();
        File vaadinHome = new File(tempDir, ".vaadin");
        vaadinHome.mkdir();
        System.setProperty("user.home", tempDir.getAbsolutePath()); //Change the home location
        String newKey = ProjectHelpers.getUserKey();
        assertNotNull(newKey);
        assertNotSame(keyString,newKey);
        File userKeyFile = new File(vaadinHome, "userKey");
        Assert.assertTrue("userKey should be created automatically",
                userKeyFile.exists());
    }

    @Test
    public void testReadProKey() {
        ApplicationConfiguration configuration = mockAppConfig(true);
        String mavenProjectFolder = TestUtils.getTestFolder("stats-data/maven-project-folder1").toPath().toString();
        System.setProperty("user.home", TestUtils.getTestFolder("stats-data").toPath().toString()); //Change the home location
        DevModeUsageStatistics.init(configuration, mavenProjectFolder);

        // File is used by default
        String keyStringFile = "test@vaadin.com/pro-536e1234-test-test-file-f7a1ef311234";
        String keyFile = ProjectHelpers.getProKey();
        assertEquals(keyStringFile, "test@vaadin.com/"+keyFile);

        // Check system property works
        String keyStringProp = "test@vaadin.com/pro-536e1234-test-test-prop-f7a1ef311234";
        System.setProperty("vaadin.proKey", keyStringProp);
        String keyProp = ProjectHelpers.getProKey();
        assertEquals(keyStringProp, "test@vaadin.com/"+keyProp);
    }

    @Test
    public void testLoadStatisticsDisabled() throws Exception {

        // Make sure by default statistics are enabled
        ApplicationConfiguration configuration = mockAppConfig(true);
        Assert.assertFalse(configuration.isProductionMode());
        Assert.assertTrue(configuration.isUsageStatisticsEnabled());

        // Initialize the statistics from Maven project
        String mavenProjectFolder = TestUtils.getTestFolder("stats-data/maven-project-folder1").toPath().toString();
        DevModeUsageStatistics.init(configuration, mavenProjectFolder);

        // Make sure statistics are enabled
        Assert.assertTrue(DevModeUsageStatistics.isStatisticsEnabled());

        // Disable statistics in config
        Mockito.when(configuration.isUsageStatisticsEnabled()).thenReturn(false);

        // Reinit
        DevModeUsageStatistics.init(configuration, mavenProjectFolder);

        // Make sure statistics are disabled
        Assert.assertFalse(DevModeUsageStatistics.isStatisticsEnabled());

        // Enable statistics in config and enable production mode
        Mockito.when(configuration.isUsageStatisticsEnabled()).thenReturn(true);
        Mockito.when(configuration.isProductionMode()).thenReturn(true);

        // Reinit
        DevModeUsageStatistics.init(configuration, mavenProjectFolder);

        // Make sure statistics are disabled in production mode
        Assert.assertFalse(DevModeUsageStatistics.isStatisticsEnabled());
    }

    private ApplicationConfiguration mockAppConfig(boolean enabled) {
        ApplicationConfiguration appConfig = Mockito
                .mock(ApplicationConfiguration.class);
        Mockito.when(appConfig.getPropertyNames())
                .thenReturn(Collections.emptyEnumeration());
        Mockito.when(appConfig.isProductionMode()).thenReturn(false);
        Mockito.when(appConfig.isUsageStatisticsEnabled()).thenReturn(enabled);

        return appConfig;
    }

    /** Create a temporary file from given test resource.
     *
      * @param testResourceName
     * @return Temporary file
     * @throws IOException
     */
    private static File createTempStorage(String testResourceName) throws IOException {
        File original = new File(TestUtils.getTestResource(testResourceName).getFile());
        File result = File.createTempFile("test", "json");
        result.deleteOnExit();
        FileUtils.copyFile(original, result);
        return result;
    }

    /** Simple HttpServer for testing.
     *
     */
    public static class TestHttpServer implements AutoCloseable {

        private HttpServer httpServer;
        private String lastRequestContent;

        public TestHttpServer(int code, String response) throws Exception {
            this.httpServer = createStubGatherServlet(HTTP_PORT, code,
                    response);
        }

        private HttpServer createStubGatherServlet(int port, int status,
                String response) throws Exception {
            HttpServer httpServer = HttpServer.create(new InetSocketAddress(port),
                    0);
            httpServer.createContext("/", exchange -> {
                this.lastRequestContent = IOUtils.toString(exchange.getRequestBody(), Charset.defaultCharset());
                exchange.sendResponseHeaders(status, response.length());
                exchange.getResponseBody().write(response.getBytes());
                exchange.close();
            });
            httpServer.start();
            return httpServer;
        }

        public String getLastRequestContent() {
            return lastRequestContent;
        }


        @Override public void close() throws Exception {
            if (httpServer != null) {
                httpServer.stop(0);
            }
        }

    }

}

