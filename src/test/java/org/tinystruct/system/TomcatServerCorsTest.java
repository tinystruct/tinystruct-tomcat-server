package org.tinystruct.system;

import org.junit.jupiter.api.*;
import org.tinystruct.AbstractApplication;
import org.tinystruct.ApplicationContext;
import org.tinystruct.net.URLRequest;
import org.tinystruct.net.URLResponse;
import org.tinystruct.net.handlers.HTTPHandler;
import org.tinystruct.system.annotation.Action;

import java.net.Socket;
import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class TomcatServerCorsTest {

    private static final int TEST_PORT = 18081;
    private static final String BASE_URL = "http://localhost:" + TEST_PORT;
    private TomcatServer httpServer;
    private Thread serverThread;
    private TestWebApp app;

    @BeforeAll
    public void setUp() throws Exception {
        // Initialize settings
        Settings settings = new Settings();
        settings.set("default.base_url", "/?q=");
        settings.set("default.language", "en_US");
        settings.set("charset", "utf-8");
        settings.set("cors.allowed.origins", "http://localhost:52819,http://localhost:8080");
        settings.set("cors.allow.credentials", "true");

        // Create and install test app
        this.app = new TestWebApp();
        ApplicationManager.install(this.app, settings);

        // Install required applications
        ApplicationManager.install(new Dispatcher());
        this.httpServer = new TomcatServer();
        ApplicationManager.install(this.httpServer, settings);

        // Start server in a separate thread
        serverThread = new Thread(() -> {
            try {
                ApplicationContext context = new ApplicationContext();
                context.setAttribute("--server-port", String.valueOf(TEST_PORT));
                ApplicationManager.call("start", context, Action.Mode.CLI);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();

        // Wait for server to be ready
        boolean started = false;
        for (int i = 0; i < 30; i++) {
            try (Socket socket = new Socket("localhost", TEST_PORT)) {
                started = true;
                break;
            } catch (Exception e) {
                Thread.sleep(1000);
            }
        }
        if (!started) {
            throw new RuntimeException("Server failed to start within 30 seconds");
        }

        // Give server a moment to fully initialize
        Thread.sleep(500);
    }

    @AfterAll
    public void tearDown() {
        if (httpServer != null) {
            httpServer.stop();
        }
        if (serverThread != null && serverThread.isAlive()) {
            serverThread.interrupt();
        }
    }

    @Test
    public void testCorsHeaderWithAllowedOrigin() throws Exception {
        String origin = "http://localhost:52819";
        URLRequest request = new URLRequest(URI.create(BASE_URL + "/?q=api/ping").toURL());
        request.setMethod("GET");
        request.setHeader("Origin", origin);

        HTTPHandler handler = new HTTPHandler();
        URLResponse response = handler.handleRequest(request);

        Map<String, List<String>> headers = response.getHeaders();
        assertTrue(headers.containsKey("Access-Control-Allow-Origin"), "Response should contain Access-Control-Allow-Origin header");
        assertEquals(origin, headers.get("Access-Control-Allow-Origin").get(0), "Access-Control-Allow-Origin should match the requested origin");
        assertTrue(headers.containsKey("Access-Control-Allow-Credentials"), "Response should contain Access-Control-Allow-Credentials header");
        assertEquals("true", headers.get("Access-Control-Allow-Credentials").get(0));
    }

    @Test
    public void testCorsHeaderWithDisallowedOrigin() throws Exception {
        String origin = "http://malicious.com";
        URLRequest request = new URLRequest(URI.create(BASE_URL + "/?q=api/ping").toURL());
        request.setMethod("GET");
        request.setHeader("Origin", origin);

        HTTPHandler handler = new HTTPHandler();
        URLResponse response = handler.handleRequest(request);

        Map<String, List<String>> headers = response.getHeaders();
        assertFalse(headers.containsKey("Access-Control-Allow-Origin"), "Response should NOT contain Access-Control-Allow-Origin header for disallowed origin");
    }

    @Test
    public void testCorsPreflight() throws Exception {
        String origin = "http://localhost:8080";
        URLRequest request = new URLRequest(URI.create(BASE_URL + "/?q=api/ping").toURL());
        request.setMethod("OPTIONS");
        request.setHeader("Origin", origin);
        request.setHeader("Access-Control-Request-Method", "POST");
        request.setHeader("Access-Control-Request-Headers", "Content-Type");

        HTTPHandler handler = new HTTPHandler();
        URLResponse response = handler.handleRequest(request);

        assertEquals(204, response.getStatusCode());
        Map<String, List<String>> headers = response.getHeaders();
        assertTrue(headers.containsKey("Access-Control-Allow-Origin"), "Preflight response should contain Access-Control-Allow-Origin header");
        assertEquals(origin, headers.get("Access-Control-Allow-Origin").get(0));
        assertTrue(headers.containsKey("Access-Control-Allow-Methods"), "Preflight response should contain Access-Control-Allow-Methods header");
        assertTrue(headers.get("Access-Control-Allow-Methods").get(0).contains("POST"));
    }

    public class TestWebApp extends AbstractApplication {
        @Override
        public void init() {
            this.setTemplateRequired(false);
        }

        @Action(
                value = "api/ping",
                description = "Ping endpoint",
                mode = Action.Mode.DEFAULT
        )
        public String ping() {
            return "pong";
        }

        @Override
        public String version() {
            return "test";
        }
    }
}
