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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URL;

import netscape.javascript.JSObject;

public class BlueBirdConnector extends Application{

    static final Logger LOG = LoggerFactory.getLogger(BlueBirdConnector.class);
    private Double screen_width = 600.0;
    private Double screen_height = 700.0;

    private FrontendServer frontendServer = FrontendServer.getSharedInstance();



    public static void main(String[] args) {
        LOG.info("Ready to launch");
        launch(args);
    }

    @Override
    public void start(Stage stage) throws Exception {
        startGUI(stage);
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
                        //callbackManager.call("scanStarted");
                        RobotManager.getSharedInstance().startDiscovery();
                    }
                });

                //this makes all stages close and the app exit when the main stage is closed
                stage.setOnCloseRequest(new EventHandler<WindowEvent>() {
                    @Override
                    public void handle(WindowEvent event) {
                        RobotManager.getSharedInstance().close();
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
                LOG.debug("resource url " + indexUrl);
                LOG.debug("resource url " + indexUrl.toString());
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
