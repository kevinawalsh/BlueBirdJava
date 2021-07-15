:: Set the current app version
SET VER=3.0
:: Set the path to your copy of the javafx jmods
SET JMODS="C:\Program Files\javafx-jmods-16"

SET FILESDIR=temp


mkdir %filesdir%
copy ..\BlueBirdConnector\out\artifacts\BlueBirdConnector_jar\BlueBirdConnector.jar %filesdir%\BlueBirdConnector.jar

:: Make msi
jpackage ^
  --type msi ^
  --app-version %ver% ^
  --copyright "BirdBrain Technologies LLC" ^
  --description "BlueBird Connector from BirdBrain Technologies" ^
  --name "BlueBird Connector" ^
  --vendor "BirdBrain Technologies" ^
  --input %filesdir% ^
  --icon hummingbirdlogo.ico ^
  --module-path %jmods% ^
  --add-modules javafx.controls,javafx.web ^
  --main-jar BlueBirdConnector.jar ^
  --main-class com.birdbraintechnologies.bluebirdconnector.BlueBirdConnector ^
  --win-menu ^
  --win-shortcut ^
  --win-console

:: Cleanup
rmdir %filesdir% /S /Q
