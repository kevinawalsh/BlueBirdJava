#!/bin/sh

jar xf ~/.m2/repository/net/sf/sociaal/freetts/1.2.2/freetts-1.2.2.jar
javac --module-path . -d . module-info.java
jar --create --file freetts-1.2.2-modular.jar --manifest=META-INF/MANIFEST.MF -C . .
mv freetts-1.2.2-modular.jar ..

mvn install:install-file   -Dfile=../freetts-1.2.2-modular.jar   -DgroupId=com.sun.speech   -DartifactId=freetts   -Dversion=1.2.2-modular   -Dpackaging=jar

