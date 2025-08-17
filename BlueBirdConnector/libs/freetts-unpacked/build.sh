#!/usr/bin/env bash

cd "$(dirname "$0")" || exit 1

# Sanity check to ensure we are in the right directory
if ! grep -q "^module freetts {$" module-info.java; then
  echo "Can't verify module-info.java... maybe we are in the wrong directory?"
  exit 1
fi

echo Cleaning
rm -rf META_INF com de javafx module-info.class
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

