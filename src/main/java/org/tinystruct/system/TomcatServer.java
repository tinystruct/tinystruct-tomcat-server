/*******************************************************************************
 * Copyright  (c) 2013, 2025 James M. ZHOU
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package org.tinystruct.system;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.*;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.ContextConfig;
import org.apache.catalina.startup.Tomcat;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.tinystruct.AbstractApplication;
import org.tinystruct.ApplicationContext;
import org.tinystruct.ApplicationException;
import org.tinystruct.data.component.Builder;
import org.tinystruct.http.*;
import org.tinystruct.http.Reforward;
import org.tinystruct.http.Session;
import org.tinystruct.http.servlet.RequestBuilder;
import org.tinystruct.http.servlet.ResponseBuilder;
import org.tinystruct.mcp.MCPPushManager;
import org.tinystruct.mcp.MCPSpecification;
import org.tinystruct.system.annotation.Action;
import org.tinystruct.system.annotation.Argument;
import org.tinystruct.system.util.StringUtilities;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import org.tinystruct.http.security.JWTManager;

import java.io.*;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.tinystruct.http.Constants.*;

public class TomcatServer extends AbstractApplication implements Bootstrap {
    private final Logger logger = Logger.getLogger(TomcatServer.class.getName());
    private boolean started = false;
    private Tomcat tomcat;

    public TomcatServer() {
    }

    @Override
    public void init() {
        this.setTemplateRequired(false);
    }

    @Action(value = "start", description = "Start a Tomcat server.", options = {
            @Argument(key = "server-port", description = "Server port"),
            @Argument(key = "http.proxyHost", description = "Proxy host for http"),
            @Argument(key = "http.proxyPort", description = "Proxy port for http"),
            @Argument(key = "https.proxyHost", description = "Proxy host for https"),
            @Argument(key = "https.proxyPort", description = "Proxy port for https")
    }, example = "bin/dispatcher start --import org.tinystruct.system.TomcatServer --server-port 777", mode = Action.Mode.CLI)
    @Override
    public void start() throws ApplicationException {
        if (started) return;

        String charsetName = null;
        Settings settings = new Settings();
        if (settings.get("default.file.encoding") != null)
            charsetName = settings.get("default.file.encoding");

        if (charsetName != null && !charsetName.trim().isEmpty())
            System.setProperty("file.encoding", charsetName);

        settings.set("language", "zh_CN");
        if (settings.get("system.directory") == null)
            settings.set("system.directory", System.getProperty("user.dir"));

        try {
            // Initialize the application manager with the configuration.
            ApplicationManager.init(settings);
        } catch (ApplicationException e) {
            // Startup cannot proceed without a working ApplicationManager - fail fast
            // instead of continuing with a half-initialized application context.
            logger.log(Level.SEVERE, "Failed to initialize ApplicationManager", e);
            throw new ApplicationException("Failed to initialize ApplicationManager: " + e.getMessage(), e.getCause());
        }

        // The port that we should run on can be set into an environment variable
        // Look for that variable and default to 8080 if it isn't there.
        int webPort = 8080;
        if (settings.get("server.port") != null && !settings.get("server.port").trim().isEmpty()) {
            webPort = Integer.parseInt(settings.get("server.port"));
        }

        if (getContext() != null) {
            if (getContext().getAttribute("--http.proxyHost") != null && getContext().getAttribute("--http.proxyPort") != null) {
                System.setProperty("http.proxyHost", getContext().getAttribute("--http.proxyHost").toString());
                System.setProperty("http.proxyPort", getContext().getAttribute("--http.proxyPort").toString());
            }

            if (getContext().getAttribute("--https.proxyHost") != null && getContext().getAttribute("--https.proxyPort") != null) {
                System.setProperty("https.proxyHost", getContext().getAttribute("--https.proxyHost").toString());
                System.setProperty("https.proxyPort", getContext().getAttribute("--https.proxyPort").toString());
            }

            if (getContext().getAttribute("--server-port") != null && !getContext().getAttribute("--server-port").toString().trim().isEmpty()) {
                webPort = Integer.parseInt(getContext().getAttribute("--server-port").toString());
            }
        }

        System.out.println(ApplicationManager.call("--logo", null, Action.Mode.CLI));

        final long start = System.currentTimeMillis();
        final String webappDirLocation = ".";
        this.tomcat = new Tomcat();

        tomcat.setPort(webPort);
        tomcat.setAddDefaultWebXmlToWebapp(false);
        // getConnector() is required here: Tomcat only creates its default connector
        // lazily, the first time getConnector() is called - without this call, start()
        // below would launch a server with no connector listening on any port.
        Connector connector = tomcat.getConnector();
        connector.setPort(webPort);
        // To enable virtual threads for request processing, uncomment:
        // connector.getProtocolHandler().setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        Host host = tomcat.getHost();
        host.setConfigClass(DefaultContextConfig.class.getName());
        host.setAutoDeploy(false);
        try {
            LifecycleListener config = new DefaultContextConfig();

            String docBase = new File(webappDirLocation).getAbsolutePath();
            Context ctx = tomcat.addWebapp(host, "", docBase, config);
            initWebappDefaults(ctx);

            logger.info("Configuring app with basedir: " + docBase);

            Class<?> filterClass = DefaultHandler.class;
            FilterDef filterDef = new FilterDef();
            filterDef.setFilterClass(filterClass.getName());
            filterDef.setFilterName(filterClass.getSimpleName());
            filterDef.setAsyncSupported("true");
            ctx.addFilterDef(filterDef);

            FilterMap filterMap = new FilterMap();
            filterMap.setFilterName(filterClass.getSimpleName());
            filterMap.addURLPattern("/");
            ctx.addFilterMap(filterMap);

            tomcat.start();
            this.started = true;
            logger.info("Tomcat server (" + webPort + ") startup in " + (System.currentTimeMillis() - start) + " ms");

            // Open the default browser
            getContext().setAttribute("--url", "http://localhost:" + webPort);
            ApplicationManager.call("open", getContext(), Action.Mode.CLI);

            // Keep the server running
            logger.info("Server is running. Press Ctrl+C to stop.");

            // Add shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                logger.info("Shutting down HTTP server...");
                stop();
            }));

            tomcat.getServer().await();
        } catch (LifecycleException e) {
            throw new ApplicationException(e.getMessage(), e.getCause());
        }
    }

    private void initWebappDefaults(Context ctx) {
        // Default servlet
        Wrapper servlet = Tomcat.addServlet(
                ctx, "default", "org.apache.catalina.servlets.DefaultServlet");
        servlet.setLoadOnStartup(1);
        servlet.setOverridable(true);

        // Servlet mappings
        ctx.addServletMappingDecoded("/", "default");

        // Sessions
        ctx.setSessionTimeout(30);

        // MIME type mappings
        Tomcat.addDefaultMimeTypeMappings(ctx);
    }

    @Override
    public void stop() {
        if (tomcat != null) {
            try {
                tomcat.stop();
                tomcat.destroy();
            } catch (LifecycleException e) {
                logger.log(Level.WARNING, "Failed to stop Tomcat", e);
            } finally {
                started = false;
                tomcat = null;
            }
        }
    }

    @Action(value = "error", description = "Error page", mode = Action.Mode.HTTP_GET)
    public Object exceptionCaught() throws ApplicationException {
        Request request = (Request) getContext().getAttribute(HTTP_REQUEST);
        Response response = (Response) getContext().getAttribute(HTTP_RESPONSE);

        Reforward reforward = new Reforward(request, response);
        this.setVariable("from", reforward.getFromURL());

        Session session = request.getSession();
        if (session.getAttribute("error") != null) {
            ApplicationException exception = (ApplicationException) session.getAttribute("error");

            Throwable rootCause = exception.getRootCause();
            String message = rootCause != null ? rootCause.getMessage() : exception.getMessage();
            this.setVariable("exception.message", Objects.requireNonNullElse(message, "Unknown error"));

            StackTraceElement[] stackTrace = exception.getStackTrace();
            StringBuilder builder = new StringBuilder();
            builder.append(exception).append("\n");
            for (StackTraceElement stackTraceElement : stackTrace) {
                builder.append(stackTraceElement.toString()).append("\n");
            }
            logger.severe(builder.toString());

            return this.getVariable("exception.message").getValue().toString();
        } else {
            reforward.forward();
        }

        return "This request is forbidden!";
    }

    @Override
    public String version() {
        return "";
    }

    static class DefaultContextConfig extends ContextConfig {
        private static final Log log = LogFactory.getLog(ContextConfig.class);

        @Override
        protected synchronized void configureStart() {
            // Called from StandardContext.start()

            if (log.isDebugEnabled()) {
                log.debug(sm.getString("contextConfig.start"));
            }

            if (log.isDebugEnabled()) {
                log.debug(sm.getString("contextConfig.xmlSettings", context.getName(), Boolean.valueOf(context.getXmlValidation()), Boolean.valueOf(context.getXmlNamespaceAware())));
            }

            if (!skipWebXmlFileScan()) webConfig();

            if (!context.getIgnoreAnnotations()) {
                applicationAnnotationsConfig();
            }

            if (ok) {
                validateSecurityRoles();
            }

            // Configure an authenticator if we need one
            if (ok) {
                authenticatorConfig();
            }

            // Dump the contents of this pipeline if requested
            if (log.isDebugEnabled()) {
                log.debug("Pipeline Configuration:");
                Pipeline pipeline = context.getPipeline();
                Valve[] valves = null;
                if (pipeline != null) {
                    valves = pipeline.getValves();
                }
                if (valves != null) {
                    for (Valve valve : valves) {
                        log.debug("  " + valve.getClass().getName());
                    }
                }
                log.debug("======================");
            }

            // Make our application available if no problems were encountered
            if (ok) {
                context.setConfigured(true);
            } else {
                log.error(sm.getString("contextConfig.unavailable"));
                context.setConfigured(false);
            }
        }

        public boolean skipWebXmlFileScan() {
            return true;
        }
    }

    /**
     * DefaultHandler is responsible for handling HTTP requests and managing the application's lifecycle.
     */
    public static class DefaultHandler extends HttpServlet implements Bootstrap, Filter {
        private static final Logger logger = Logger.getLogger(DefaultHandler.class.getName());
        private static final long serialVersionUID = 0;
        private static final String DATE_FORMAT_PATTERN = "yyyy-M-d h:m:s";
        private static final SimpleDateFormat format = new SimpleDateFormat(DATE_FORMAT_PATTERN);
        private String charsetName;
        private Charset writerCharset;
        private Configuration<String> settings;
        private String path;
        private volatile boolean sseManagerUsed;
        private volatile boolean mcpManagerUsed;

        @Override
        public void init(ServletConfig config) {
            this.path = config.getServletContext().getRealPath("");
            try {
                this.start();
            } catch (ApplicationException e) {
                logger.severe(e.getMessage());
            }

            logger.info("Initialized servlet config and starting...");
        }

        @Override
        public void init(FilterConfig config) throws ServletException {
            this.path = config.getServletContext().getRealPath("");
            try {
                this.start();
            } catch (ApplicationException e) {
                logger.severe(e.getMessage());
            }

            logger.info("Initialized filter config and starting...");
        }

        @Override
        public void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
            request.setCharacterEncoding(charsetName);
            if (!isSSE(request)) {
                response.setContentType("text/html;charset=" + charsetName);
            }
            response.setCharacterEncoding(charsetName);
            response.setHeader("Pragma", "No-cache");
            response.setHeader("Cache-Control", "No-cache");
            response.setDateHeader("Expires", 0);

            final org.tinystruct.application.Context context = new ApplicationContext();
            Request<HttpServletRequest, ServletInputStream> _request = new RequestBuilder(request, isSSL());
            Response<HttpServletResponse, ServletOutputStream> _response = new ResponseBuilder(response);

            // Enforce server name restriction if configured
            String configuredServerName = settings.get("server.name");
            if (configuredServerName != null && !configuredServerName.trim().isEmpty()) {
                String hostHeader = request.getHeader("Host");
                boolean hostAllowed = false;
                if (hostHeader != null) {
                    for (String allowed : configuredServerName.split(",")) {
                        if (hostHeader.equalsIgnoreCase(allowed.trim())) {
                            hostAllowed = true;
                            break;
                        }
                    }
                }
                if (!hostAllowed) {
                    logger.warning("Rejected request: Host header '" + hostHeader + "' does not match any configured server.name '" + configuredServerName.trim() + "'");
                    sendErrorResponse(_request, _response, ResponseStatus.BAD_REQUEST, "Bad Request: Invalid server name.");
                    return;
                }
            }

            // Authenticate request
            if (!authenticateRequest(_request, context)) {
                sendErrorResponse(_request, _response, ResponseStatus.UNAUTHORIZED, "Invalid or expired token.");
                return;
            }

            try {
                context.setId(_request.getSession().getId());
                context.setAttribute(HTTP_REQUEST, _request);
                context.setAttribute(HTTP_RESPONSE, _response);
                context.setAttribute(HTTP_SCHEME, request.getScheme());
                context.setAttribute(HTTP_SERVER, request.getServerName());
                context.setAttribute(HTTP_PROTOCOL, getProtocol(request));

                String lang = _request.getParameter("lang");
                if (lang != null && !lang.trim().isEmpty()) {
                    String name = lang.replace('-', '_');

                    if (Language.support(name) && !lang.equalsIgnoreCase(this.settings.get("language"))) {
                        context.setAttribute(LANGUAGE, name);
                    }
                }

                String url_prefix = "/";
                if (this.settings.get("default.url_rewrite") != null && !"enabled".equalsIgnoreCase(this.settings.get("default.url_rewrite"))) {
                    url_prefix = "/?q=";
                }

                context.setAttribute(HTTP_HOST, getHost(request) + url_prefix);

                String[] parameterNames = _request.parameterNames();
                for (String parameter : parameterNames) {
                    if (parameter.startsWith("--")) {
                        context.setAttribute(parameter, request.getParameter(parameter));
                    }
                }

                Object originObj = _request.headers().get(Header.ORIGIN);
                String origin = originObj != null ? originObj.toString() : null;

                String allowOrigin = getAllowOrigin(origin);
                if (allowOrigin != null && !allowOrigin.isEmpty()) {
                    _response.addHeader("Access-Control-Allow-Origin", allowOrigin);
                }

                // Make responses vary by Origin when echoing it
                if (origin != null) {
                    _response.addHeader("Vary", "Origin");
                }

                // Allow credentials if explicitly enabled in settings
                if ("true".equalsIgnoreCase(settings.get("cors.allow.credentials"))) {
                    _response.addHeader("Access-Control-Allow-Credentials", "true");
                }

                // Expose specific headers for clients to read (e.g. MCP session ID)
                String exposeHeaders = settings.getOrDefault("cors.exposed.headers", MCPSpecification.Http.SESSION_ID + "," + MCPSpecification.Http.CONVERSATION_ID);
                _response.addHeader("Access-Control-Expose-Headers", exposeHeaders);

                // Handle CORS preflight (OPTIONS) requests up-front: these have no body.
                if ("OPTIONS".equalsIgnoreCase(_request.method().name())) {
                    Object requestedMethod = _request.headers().get(Header.ACCESS_CONTROL_REQUEST_METHOD);
                    Object requestedHeaders = _request.headers().get(Header.ACCESS_CONTROL_REQUEST_HEADERS);
                    String acrMethod = requestedMethod != null ? requestedMethod.toString() : null;
                    String acrHeaders = requestedHeaders != null ? requestedHeaders.toString() : null;

                    // Allow methods: prefer configured list, otherwise echo requested or use sensible defaults
                    String allowMethods = settings.getOrDefault("cors.allowed.methods", acrMethod != null ? acrMethod : "GET,POST,PUT,DELETE,OPTIONS,PATCH");
                    _response.addHeader("Access-Control-Allow-Methods", allowMethods);

                    // Allow headers: prefer configured list, otherwise echo requested or common headers
                    String allowHeaders = settings.getOrDefault("cors.allowed.headers", acrHeaders != null ? acrHeaders : "Content-Type,Authorization");
                    _response.addHeader("Access-Control-Allow-Headers", allowHeaders);

                    // Cache the preflight response for a configurable duration (seconds)
                    String maxAge = settings.getOrDefault("cors.preflight.maxage", "3600");
                    _response.addHeader("Access-Control-Max-Age", maxAge);

                    // No response body for preflight; return 204 No Content
                    _response.setStatus(ResponseStatus.NO_CONTENT);

                    return;
                }

                if (isSSE(request)) {
                    handleSSE(context, request, response, _request, _response);
                    return;
                }

                String query = _request.getParameter("q");
                if (query != null) {
                    Action.Mode mode = Action.Mode.fromName(request.getMethod());
                    handleRequest(query, context, _request, _response, mode);
                } else {
                    handleDefaultPage(context, _response);
                }
            } catch (ApplicationException e) {
                try {
                    handleApplicationException(_request, _response, e);
                } catch (ApplicationException ex) {
                    logger.log(Level.SEVERE, ex.getMessage(), ex);
                }
            }
        }

        /**
         * Helper to select the appropriate push manager based on isMCP flag.
         */
        private SSEPushManager getAppropriatePushManager(boolean isMCP) {
            return isMCP ? MCPPushManager.getInstance() : SSEPushManager.getInstance();
        }

        private void handleSSE(org.tinystruct.application.Context context, HttpServletRequest _request,
                               HttpServletResponse _response,
                               Request<HttpServletRequest, ServletInputStream> request,
                               Response<HttpServletResponse, ServletOutputStream> response) throws IOException {
            response.addHeader(Header.CONTENT_TYPE.name(), "text/event-stream; charset=utf-8");
            response.addHeader(Header.CACHE_CONTROL.name(), "no-cache");
            response.addHeader(Header.CONNECTION.name(), "keep-alive");
            response.addHeader("X-Accel-Buffering", "no");

            // Switch the servlet to async mode BEFORE calling any application code that
            // may enqueue messages.  Without this, Tomcat commits and closes the response
            // as soon as this method returns, so the SSEClient background thread would be
            // writing to an already-closed stream and every message would be lost.
            jakarta.servlet.AsyncContext asyncContext = _request.startAsync();
            asyncContext.setTimeout(0); // No timeout — SSE connections are long-lived.

            try {
                String query = request.getParameter("q");
                boolean isMCP = false;
                if (query != null) {
                    query = StringUtilities.htmlSpecialChars(query);
                    if (query.equals(MCPSpecification.Endpoints.SSE)) {
                        isMCP = true;
                    }

                    Method method = request.method();
                    Action.Mode mode = Action.Mode.fromName(method.name());
                    Object call = ApplicationManager.call(query, context, mode);
                    String sessionId = context.getId();
                    if (isMCP) {
                        mcpManagerUsed = true;
                    } else {
                        sseManagerUsed = true;
                    }
                    SSEPushManager pushManager = getAppropriatePushManager(isMCP);
                    response.setStatus(ResponseStatus.OK);
                    Object registration = pushManager.register(sessionId, response);

                    if (call instanceof Builder) {
                        pushManager.push(sessionId, (Builder) call);
                    } else if (call instanceof String) {
                        Builder builder = new Builder();
                        builder.parse((String) call);
                        pushManager.push(sessionId, builder);
                    }

                    if (registration instanceof SSEClient sseClient) {
                        // The SSEClient thread drives the connection. We must complete the
                        // AsyncContext once it finishes (disconnect / broken pipe / shutdown).
                        // We submit a small wrapper on the same executor so it does not block
                        // the servlet thread-pool at all.
                        final jakarta.servlet.AsyncContext ac = asyncContext;
                        asyncContext.start(() -> {
                            try {
                                // Wait for the SSEClient worker to finish.
                                while (sseClient.isActive()) {
                                    Thread.sleep(500);
                                }
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                logger.warning("SSE async watcher interrupted for session: " + sessionId);
                            } finally {
                                try {
                                    ac.complete();
                                } catch (Exception ignore) {
                                    // AsyncContext may already be completed on disconnect
                                }
                                logger.fine("SSE async context completed for session: " + sessionId);
                            }
                        });
                    } else {
                        // No SSEClient was registered (e.g. session already active);
                        // complete immediately so the connection is not leaked.
                        asyncContext.complete();
                    }
                } else {
                    asyncContext.complete();
                }
            } catch (ApplicationException e) {
                try {
                    if (!_response.isCommitted()) {
                        _response.resetBuffer();
                        _response.setStatus(ResponseStatus.INTERNAL_SERVER_ERROR.code());
                        _response.setContentType("text/plain");
                        _response.setCharacterEncoding("UTF-8");
                        _response.getWriter().write("SSE request failed");
                    }
                } catch (Exception ignore) {
                    // ignore
                } finally {
                    try {
                        asyncContext.complete();
                    } catch (Exception ignore) {
                        // ignore
                    }
                }
                logger.log(Level.WARNING, "SSE request failed", e);
            }
        }

        private String getAllowOrigin(String origin) {
            // Get the configured allowed origins.
            String allowedOrigins = settings.get("cors.allowed.origins");

            if (allowedOrigins == null || allowedOrigins.trim().isEmpty()) {
                return origin != null ? origin : "*";
            }

            if ("*".equals(allowedOrigins)) {
                // If credentials are allowed, we MUST echo the origin instead of returning "*"
                if ("true".equalsIgnoreCase(settings.get("cors.allow.credentials"))) {
                    return origin != null ? origin : "*";
                }
                return "*";
            }

            if (origin != null) {
                String[] origins = allowedOrigins.split(",");
                for (String allowed : origins) {
                    if (origin.equalsIgnoreCase(allowed.trim())) {
                        return origin;
                    }
                }
            }

            return null;
        }

        private boolean authenticateRequest(Request<?, ?> request, org.tinystruct.application.Context context) {
            Object authorization;
            if ((authorization = request.headers().get(Header.AUTHORIZATION)) != null) {
                String authHeader = authorization.toString();
                if (authHeader != null && authHeader.startsWith("Bearer ")) {
                    String token = authHeader.substring(7);

                    String secret = settings.get("jwt.secret");
                    if (secret == null || secret.trim().isEmpty()) {
                        // jwt.secret is not configured — cannot validate Bearer token.
                        // Log a warning and reject the request to avoid using a weak/empty key.
                        logger.warning("jwt.secret is not configured. " +
                                "Bearer token authentication is disabled. " +
                                "Please set jwt.secret (>= 256-bit) in application.properties.");
                        return false;
                    }

                    JWTManager jwtManager = new JWTManager();
                    jwtManager.withBase64Secret(secret);

                    String timezone = settings.get("jwt.timezone");
                    if (timezone != null && !timezone.trim().isEmpty()) {
                        try {
                            jwtManager.withTimezone(timezone);
                        } catch (NumberFormatException e) {
                            logger.warning("Invalid jwt.timezone value: " + timezone);
                        }
                    }

                    try {
                        Jws<Claims> claims = jwtManager.parseToken(token);
                        context.setAttribute("CLAIMS", claims);
                        return true;
                    } catch (JwtException e) {
                        // Log authentication failure
                        logger.warning("JWT validation failed: " + e.getMessage());
                        return false;
                    }
                }
            }
            return true; // Allow requests without a token
        }

        private void sendErrorResponse(Request<?, ?> request, Response<HttpServletResponse, ServletOutputStream> response, ResponseStatus status, String message) {
            try {
                Object originObj = request.headers().get(Header.ORIGIN);
                if (originObj != null) {
                    String origin = originObj.toString();
                    response.addHeader("Vary", "Origin");
                    String allowOrigin = getAllowOrigin(origin);
                    if (allowOrigin != null) {
                        response.addHeader("Access-Control-Allow-Origin", allowOrigin);
                    }
                }

                response.setStatus(status);
                response.addHeader(Header.CONTENT_TYPE.name(), "text/plain; charset=UTF-8");
                byte[] body = (message != null ? message : "Unknown error").getBytes(java.nio.charset.StandardCharsets.UTF_8);
                response.writeAndFlush(body);
            } catch (ApplicationException e) {
                logger.log(Level.WARNING, "Error sending error response", e);
            }
        }

        /**
         * Handles the HTTP request by processing the query.
         *
         * @param query    The query string
         * @param context  The application context
         * @param response The HTTP response object
         * @param mode
         * @throws IOException if an I/O error occurs
         */
        private void handleRequest(String query, org.tinystruct.application.Context context, Request request, Response<HttpServletResponse, ServletOutputStream> response, Action.Mode mode) throws IOException, ApplicationException {
            // Handle request
            query = StringUtilities.htmlSpecialChars(query);
            Object message = ApplicationManager.call(query, context, mode);
            if (message != null) {
                if (message instanceof byte[] bytes) {
                    response.addHeader(Header.CONTENT_LENGTH.name(), String.valueOf(bytes.length));
                    response.get().write(bytes);
                } else {
                    try (BufferedWriter bufferedWriter = getWriter(response.get())) {
                        bufferedWriter.write(String.valueOf(message));
                    }
                }
            } else {
                try (BufferedWriter bufferedWriter = getWriter(response.get())) {
                    bufferedWriter.write("No response retrieved!");
                }
            }
        }

        /**
         * Handles the default page request.
         *
         * @param context  The application context
         * @param response The HTTP response object
         * @throws IOException if an I/O error occurs
         */
        private void handleDefaultPage(org.tinystruct.application.Context context, Response<HttpServletResponse, ServletOutputStream> response) throws IOException, ApplicationException {
            try (BufferedWriter bufferedWriter = getWriter(response.get())) {
                bufferedWriter.write(String.valueOf(ApplicationManager.call(settings.getOrDefault("default.home.page", "say/Praise the Lord."), context, Action.Mode.HTTP_GET)));
            }
        }

        /**
         * Handles application exceptions.
         *
         * @param request  The HTTP servlet request
         * @param response The HTTP servlet response
         * @param e        The application exception
         */
        private void handleApplicationException(Request<HttpServletRequest, ServletInputStream> request, Response<HttpServletResponse, ServletOutputStream> response, ApplicationException e) throws ApplicationException {
            response.setStatus(ResponseStatus.valueOf(e.getStatus()));
            Session session = request.getSession();
            session.setAttribute("error", e);
            if (!Boolean.parseBoolean(settings.get("default.error.process"))) {
                String defaultErrorPage = settings.get("default.error.page");
                Reforward forward = new Reforward(request, response);
                forward.setDefault(defaultErrorPage == null || defaultErrorPage.trim().isEmpty() ? "/?q=error" : "/?q=" + defaultErrorPage.trim());
                forward.forward();
            }
        }

        @Override
        public void start() throws ApplicationException {
            settings = new Settings();
            charsetName = settings.getOrDefault("default.file.encoding", Charset.defaultCharset().name());
            writerCharset = Charset.forName(charsetName);
            settings.setIfAbsent("language", "zh_CN");
            settings.setIfAbsent("system.directory", path);
        }

        private boolean isSSL() {
            String sslEnabled = settings.get("ssl.enabled");
            return Boolean.parseBoolean(sslEnabled);
        }

        private String getProtocol(HttpServletRequest request) {
            // Use the request object to determine the protocol
            return request.isSecure() || isSSL() ? "https://" : "http://";
        }

        private String getHost(HttpServletRequest request) {
            int serverPort = request.getServerPort();
            String defaultHostName = request.getServerName();
            String protocol = getProtocol(request);

            if (serverPort == 80) {
                return protocol + defaultHostName;
            } else {
                return protocol + defaultHostName + ":" + serverPort;
            }
        }

        /**
         * Use BufferedWriter for top efficiency.
         *
         * @param out OutputStream
         * @return BufferedWriter
         */
        private BufferedWriter getWriter(OutputStream out) {
            return new BufferedWriter(new OutputStreamWriter(out, writerCharset));
        }

        @Override
        public void stop() {
            if (sseManagerUsed) {
                SSEPushManager.getInstance().shutdown();
            }
            if (mcpManagerUsed) {
                MCPPushManager.getInstance().shutdown();
            }
            System.out.println("Stopping...");
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
            final HttpServletRequest req = (HttpServletRequest) request;
            final HttpServletResponse resp = (HttpServletResponse) response;
            if (isSSE(req)) {
                this.service(req, resp);
                return;
            }
            final long now = System.currentTimeMillis();
            resp.addHeader("Cache-Control", "public, max-age=86400, must-revalidate");
            resp.setDateHeader("Expires", now + 86400000L);

            // Process the static files in the project
            String uri = req.getRequestURI().replaceAll("^/+", "");
            if (uri.length() > 1) {
                // Decode percent-encoded characters BEFORE slicing out the first path
                // segment, otherwise an encoded '/' or '.' has no effect on which
                // segment gets checked below (the decode used to run after the slice).
                uri = uri.replaceAll("(?i)%2e", ".")
                        .replaceAll("(?i)%2f", "/")
                        .replaceAll("(?i)%5c", "/");

                if (uri.indexOf('/') != -1)
                    uri = uri.substring(0, uri.indexOf("/"));

                // Reject any segment that could escape the current directory.
                if (uri.equals("..") || uri.equals(".") || uri.contains("..")) {
                    this.service(req, resp);
                    return;
                }

                File resource = new File(uri);
                if (resource.exists()) {
                    chain.doFilter(request, response);
                } else {
                    this.service(req, resp);
                }
            } else
                this.service(req, resp);
        }

        private boolean isSSE(HttpServletRequest request) {
            String acceptHeader = request.getHeader("Accept");
            return acceptHeader != null && acceptHeader.contains("text/event-stream");
        }

        @Override
        public void destroy() {
            this.stop();
        }

        @Override
        public void run() {
            //TODO
        }
    }
}