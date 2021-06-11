module BlueBirdConnector {
    requires javafx.controls;
    requires javafx.web;
    requires org.slf4j;
    requires jdk.jsobject;
    requires org.json;
    requires org.eclipse.jetty.server;
    requires org.eclipse.jetty.servlet;
    requires org.eclipse.jetty.servlets;
    requires java.desktop;

    exports com.birdbraintechnologies.bluebirdconnector;
}