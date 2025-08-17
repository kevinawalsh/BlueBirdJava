#!/usr/bin/env bash

cd "$(dirname "$0")" || exit 1

# Sanity check to ensure we are in the right directory
if ! grep -q "^module slf4j.nop {$" module-info.java; then
  echo "Can't verify module-info.java... maybe we are in the wrong directory?"
  exit 1
fi

echo Cleaning
rm -rf META_INF org module-info.class
echo Un-jaring
jar xf ~/.m2/repository/org/slf4j/slf4j-nop/2.0.0-alpha1/slf4j-nop-2.0.0-alpha1.jar
echo Fixup # Remove any existing versioned module-info
rm -rf META-INF/versions/9/module-info.class 2>/dev/null || true
rmdir -p META-INF/versions/9 2>/dev/null || true
echo Compiling
javac --module-path ~/.m2/repository/org/slf4j/slf4j-api/2.0.0-alpha1 -d . module-info.java
echo Jaring
jar --create --file slf4j-nop-2.0.0-alpha1-modular.jar --manifest=META-INF/MANIFEST.MF -C . .
echo Moving
mv slf4j-nop-2.0.0-alpha1-modular.jar ..
echo Installing
mvn install:install-file   -Dfile=../slf4j-nop-2.0.0-alpha1-modular.jar   -DgroupId=org.slf4j   -DartifactId=slf4j-nop   -Dversion=2.0.0-alpha1-modular   -Dpackaging=jar

