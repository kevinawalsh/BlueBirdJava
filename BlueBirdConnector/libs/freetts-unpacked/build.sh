#!/bin/sh

echo Un-jaring
jar xf ~/.m2/repository/net/sf/sociaal/freetts/1.2.2/freetts-1.2.2.jar
echo Compiling
javac --module-path . -d . module-info.java
echo Jaring
jar --create --file freetts-1.2.2-modular.jar --manifest=META-INF/MANIFEST.MF -C . .
echo Moving
mv freetts-1.2.2-modular.jar ..
echo Installing
mvn install:install-file   -Dfile=../freetts-1.2.2-modular.jar   -DgroupId=com.sun.speech   -DartifactId=freetts   -Dversion=1.2.2-modular   -Dpackaging=jar

