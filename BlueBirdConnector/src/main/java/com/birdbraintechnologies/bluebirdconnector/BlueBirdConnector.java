package com.birdbraintechnologies.bluebirdconnector;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.event.EventHandler;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.image.Image;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.server.*;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlets.CrossOriginFilter;

import java.io.*;
import java.net.URL;
import java.util.*;

import netscape.javascript.JSObject;

import static com.birdbraintechnologies.bluebirdconnector.Utilities.stackTraceToString;

public class BlueBirdConnector extends Application{

    static final Log LOG = Log.getLogger(BlueBirdConnector.class);
    //static final Properties prop = new Properties();

    private Double screen_width = 800.0;
    private Double screen_height = 700.0;
    private boolean useTTS = false;

    private FrontendServer frontendServer = FrontendServer.getSharedInstance();
    private RobotManager robotManager = RobotManager.getSharedInstance();
    private Thread webServerThread;


    public static void main(String[] args) {
        String argString = "no arguments";
        if (args.length > 0) {
            argString = "with arguments ";
            for (int i = 0; i < args.length; i++){
                argString += args[i] + " ";
            }
        }

        LOG.info("Ready to launch - " + argString);
        launch(args);
    }

    @Override
    public void start(Stage stage) throws Exception {
        String[] args = getParameters().getRaw().toArray(new String[0]);
        if (args.length > 0) {
            robotManager.setupTTS(args);
            useTTS = true;
        }

        startGUI(stage);
        startHttpServer();
    }


    private void startGUI (Stage stage) {
        Platform.runLater(() -> {
            try {
                Platform.setImplicitExit(false);
                LOG.debug("Starting GUI");
                WebView webView = new WebView();
                final WebEngine webEngine = webView.getEngine();

                webEngine.getLoadWorker().stateProperty().addListener((observable, oldValue, newValue) -> {
                    if (newValue == Worker.State.SUCCEEDED) {
                        LOG.debug("setting up frontend communications...");
                        JSObject window = (JSObject) webEngine.executeScript("window");
                        window.setMember("javaConnector", frontendServer);

                        JSObject callbackManager = (JSObject) webEngine.executeScript("getCallbackManager()");
                        LOG.debug("callbackManager = " + callbackManager.toString());
                        frontendServer.setCallbackManager(callbackManager);
                        frontendServer.setGuiTts(useTTS);

                        //String language = Locale.getDefault().getLanguage();
                        String countryCode = System.getProperty("user.country");
                        String language = System.getProperty("user.language");
                        // Get Chinese variant. Must end up being either "zh-Hant" or "zh-Hans"
                        if (language.startsWith("zh")) {
                            if (countryCode.equals("TW"))
                                language = "zh-Hant"; // Taiwan: set traditional Chinese
                            else
                                language = "zh-Hans"; // Default to simplified chinese for any other variant
                        }
                        // Mac representation of Hebrew, convert to "he"
                        if (language.equals("iw"))
                            language = "he";
                        frontendServer.setTranslationTable(language);

                        //robotManager.startDiscovery();
                    }
                });

                //this makes all stages close and the app exit when the main stage is closed
                stage.setOnCloseRequest(new EventHandler<WindowEvent>() {
                    @Override
                    public void handle(WindowEvent event) {
                        robotManager.close();
                        Platform.exit();
                        System.exit(0);
                    }
                });

                // Get screen size
                Rectangle2D screenBounds = Screen.getPrimary().getVisualBounds();

                if (screen_width > screenBounds.getWidth())
                    screen_width = screenBounds.getWidth() - 50;
                if (screen_height > screenBounds.getHeight())
                    screen_height = screenBounds.getHeight() - 50;
                // get screen center
                double xCenter = (screenBounds.getWidth() - screen_width) / 2;
                double yCenter = (screenBounds.getHeight() - screen_height) / 2;

                stage.setX(xCenter);
                stage.setY(yCenter);

                webView.setPrefSize(screen_width, screen_height);
                stage.setScene(new Scene(webView));
                Thread.sleep (500);
                LOG.debug("Showing Stage");
                webView.getEngine().setJavaScriptEnabled(true);

                URL indexUrl = this.getClass().getResource("/frontend/index.html");
                webView.getEngine().load(indexUrl.toString());

                stage.setTitle("Bluebird Connector");
                URL iconUrl = this.getClass().getResource("/frontend/img/hummingbirdlogo32x32.png");
                String url = iconUrl.toString();
                Image img = new Image(url);
                stage.getIcons().add(img);
                stage.show();
            } catch (Exception ex) {
                LOG.error("Cannot Start GUI");
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                ex.printStackTrace(pw);
                String sStackTrace = sw.toString(); // stack trace as a string
                LOG.error("{}", sStackTrace);
                showErrorDialog("ERROR", "Cannot Start GUI", "The app encountered the following  error and had to stop: \n\n" + sStackTrace, true);
            }
        });
    }

    public void startHttpServer() {

        webServerThread = new Thread() {
            public Server server;
            @Override
            public void run() {
                //Thread thisThread = Thread.currentThread();
                try {
                    LOG.info("Starting Web Server");
                    server = new Server();

                    // HTTPS Configuration
                    HttpConfiguration http_config = new HttpConfiguration();

                    ServerConnector http = new ServerConnector(server,
                            new HttpConnectionFactory(http_config));
                    http.setPort(30061);
                    http.setIdleTimeout(-1);

                    server.setConnectors(new Connector[] {http});

                    // Setup the basic application "context" for this application at "/"
                    // This is also known as the handler tree (in jetty speak)
                    ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
                    context.setContextPath("/");
                    //context.setResourceBase(".");
                    URL snapUrl = this.getClass().getResource("/Snap-6.1.4"); // BAD here
                    LOG.info("snapURL is " + snapUrl);
                    LOG.debug("snapURL: {}", snapUrl);
                    context.setResourceBase(snapUrl.toString());

                    FilterHolder filterHolder = new FilterHolder(CrossOriginFilter.class);
                    filterHolder.setInitParameter("allowedOrigins", "*");
                    filterHolder.setInitParameter("allowedMethods", "GET, POST");
                    context.addFilter(filterHolder, "/*", null);

                    //URL handlers
                    //add this first in case order matters as this is the most heavily used.
                    //ServletHolder hummingbird = new ServletHolder("hummingbird", hummingbirdServelet.class);
                    ServletHolder hummingbird = new ServletHolder("hummingbird", RobotServlet.class);
                    context.addServlet(hummingbird, "/hummingbird/*");

                    // Lastly, the default servlet for root content (always needed, to satisfy servlet spec)
                    // It is important that this is last.
                    ServletHolder holderPwd = new ServletHolder("default", DefaultServlet.class);
                    holderPwd.setInitParameter("dirAllowed","true");
                    context.addServlet(holderPwd,"/");

                    server.setHandler(context);

                    LOG.info("Starting Web Server on port 30061");
                    try {
                        server.start();
                    } catch (Exception e) {
                        LOG.error(e.toString());
                        //abortLaunch = true;
                        String message = "Only one instance of the Bluebird Connector can be running at a time.\n"
                                + "Shut down the currently running instance before starting a new one.\n\n"
                                + "If you are sure this is the only instance of Bluebird Connector running then "
                                + "check to see if another application is using port 30061";
                        LOG.error(message);
                        LOG.error("{}", stackTraceToString(e));

                        showErrorDialog("ERROR", "Bluebird Connector Already Running", message, true);
                    } finally {
                        /*if (!abortLaunch) {
                            blueBirdDriverThread.start();
                            ready = true;
                            //startGUI(stage);
                            //server.join();
                        }*/
                        LOG.info("Web Server started on port 30061");
                    }
                } catch (Exception e) {
                    LOG.error("startHTTPServer exception: {}", e.getMessage());
                    LOG.error("{}", stackTraceToString(e));
                    e.printStackTrace();
                    System.exit(1);
                }
            }

            public void stopServer() {
                try {
                    server.stop();
                }
                catch (Exception e) {System.out.println(e.toString());}
            }
        };

        webServerThread.start();
    }

    public static void showErrorDialog(String title, String header, String message, boolean abort) {
        Platform.runLater(() -> {
            try {
                Alert alert = new Alert(AlertType.ERROR);
                alert.setTitle(title);
                alert.setHeaderText(header);
                alert.setContentText(message);

                Stage stage = (Stage) alert.getDialogPane().getScene().getWindow();
                stage.setAlwaysOnTop(true);
                stage.toFront(); // not sure if necessary
                stage.showAndWait();
                if (abort)
                    System.exit(1);

            } catch (Exception ex) {
                LOG.error("Cannot Start Local Snap Window");
                ex.printStackTrace();
            }
        });
    }


}
