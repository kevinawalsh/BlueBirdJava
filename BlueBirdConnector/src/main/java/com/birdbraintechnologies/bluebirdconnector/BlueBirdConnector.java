package com.birdbraintechnologies.bluebirdconnector;

//WebView

//import com.creativecomputerlab.ScratchME;
//import com.sun.javafx.webkit.WebConsoleListener;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.event.EventHandler;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.image.Image;
import javafx.scene.web.WebView;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URL;

public class BlueBirdConnector extends Application{

    static final Logger LOG = LoggerFactory.getLogger(BlueBirdConnector.class);
    private Double screen_width = 600.0;
    private Double screen_height = 700.0;
    //static ScratchME scratchME = null;


    public static void main(String[] args) {
			/*scratchME = ScratchME.getInstance(args);
	        while (!ScratchME.ready) {
	        	LOG.info("Waiting for web server to start");
	        	try { Thread.sleep(1000);} catch (Exception e) {}
	        }*/
        LOG.info("Ready to launch");
        launch(args);

    }

    @Override
    public void start(Stage stage) throws Exception {
			/*screen_width = scratchME.screen_width;
			screen_height = scratchME.screen_height;*/
        startGUI(stage);
    }


    private void startGUI (Stage stage) {
        Platform.runLater(() -> {
            try {
                Platform.setImplicitExit(false);
                LOG.debug("Starting GUI");
                WebView webview = new WebView();

                // print javascript console.log messages to java console
			        /*WebConsoleListener.setDefaultListener((webView, message, lineNumber, sourceId) -> {
			            //System.out.println("WEB:: " + message + "[at " + lineNumber + "]");
						LOG.info("WEB({}:{}): {}", sourceId, lineNumber, message);
			        });*/

                //this makes all stages close and the app exit when the main stage is closed
                stage.setOnCloseRequest(new EventHandler<WindowEvent>() {
                    @Override
                    public void handle(WindowEvent event) {
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

                webview.setPrefSize(screen_width, screen_height);
                stage.setScene(new Scene(webview));
                //stage.setMaximized(true);
                Thread.sleep (500);
                LOG.debug("Showing Stage");
                webview.getEngine().setJavaScriptEnabled(true);
                //webview.getEngine().load("http://localhost:30061/prototype.html");

                URL indexUrl = this.getClass().getResource("/frontend/index.html");
                webview.getEngine().load(indexUrl.toString());
                LOG.debug("resource url " + indexUrl.toString());
                stage.setTitle("Bluebird Connector");
                //String url = "file:///"+ScratchME.userDir+"/scratchx/images/hummingbirdlogo32x32.png";
                //String url = "/frontend/img/hummingbirdlogo32x32.png";
                URL iconUrl = this.getClass().getResource("/frontend/img/hummingbirdlogo32x32.png");
                String url = iconUrl.toString();
                Image img = new Image(url);
                stage.getIcons().add(img);
                stage.show();
            } catch (Exception ex) {
                LOG.error("Cannot Start GUI");
                //LOG.error("{}", ex.toString());
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
                //alert.showAndWait();
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
