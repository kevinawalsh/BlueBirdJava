module BlueBirdConnector {
    requires javafx.controls;
    requires javafx.web;
    requires jdk.jsobject;
    requires com.google.gson;
    requires slf4j.nop;
    requires org.eclipse.jetty.server;
    requires org.eclipse.jetty.servlet;
    requires org.eclipse.jetty.servlets;
    requires java.desktop;
    requires com.fazecast.jSerialComm;
    requires java.logging;
    requires freetts;
    requires org.freedesktop.dbus;
    requires org.freedesktop.dbus.transport.jre;

    exports com.birdbraintechnologies.bluebirdconnector;
}
