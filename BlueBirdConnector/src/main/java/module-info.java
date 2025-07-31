module BlueBirdConnector {
    requires javafx.controls;
    requires javafx.web;
    requires jdk.jsobject;
    requires com.google.gson;
    requires org.eclipse.jetty.server;
    requires org.eclipse.jetty.servlet;
    requires org.eclipse.jetty.servlets;
    requires java.desktop;
    requires com.fazecast.jSerialComm;
    requires java.logging;
    requires freetts;
    requires org.freedesktop.dbus;

    exports com.birdbraintechnologies.bluebirdconnector;
}
