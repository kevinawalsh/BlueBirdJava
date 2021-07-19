@echo off
if [%1]==[] (
  echo Usage: CreateWinMsi 'signing password'
  goto :eof
)

:: Set the current app version
SET VER=3.0
:: Set the path to your copy of the javafx jmods
SET JMODS="C:\Program Files\javafx-jmods-16"

SET FILESDIR=temp

echo Copying the jar file...
mkdir %filesdir%
copy ..\BlueBirdConnector\out\artifacts\BlueBirdConnector_jar\BlueBirdConnector.jar %filesdir%\BlueBirdConnector.jar

:: Make the app image separately so that the command line application can be copied in before the msi is made.
echo Creating the app-image...
jpackage --type app-image --app-version %ver% ^
  --copyright "BirdBrain Technologies LLC" ^
  --description "BlueBird Connector from BirdBrain Technologies" ^
  --name "BlueBird Connector" ^
  --vendor "BirdBrain Technologies" ^
  --input %filesdir% ^
  --icon hummingbirdlogo.ico ^
  --module-path %jmods% ^
  --add-modules javafx.controls,javafx.web ^
  --main-jar BlueBirdConnector.jar ^
  --main-class com.birdbraintechnologies.bluebirdconnector.BlueBirdConnector

:: copy in the windows native bluetooth utility. This needs to be in the same folder as the launcher.
echo Copying the command line utility...
copy ..\BlueBirdWindowsCL\bin\Release\BlueBirdWindowsCL.exe "BlueBird Connector\BlueBirdWindowsCL.exe"

echo Signing BlueBirdConnector.jar, BlueBird Connector.exe, and BlueBirdWindowsCL.exe...
jarsigner -storetype pkcs12 -keystore BIRDBRAIN.pfx -storepass %1 "BlueBird Connector\app\BlueBirdConnector.jar" 73cfaf53eaee4153b44e02ca7b2a7e76
attrib -r "BlueBird Connector\BlueBird Connector.exe"
signtool sign /fd SHA256 /f BIRDBRAIN.pfx /p %1 "BlueBird Connector\BlueBird Connector.exe"
attrib +r "BlueBird Connector\BlueBird Connector.exe"
signtool sign /fd SHA256 /f BIRDBRAIN.pfx /p %1 "BlueBird Connector\BlueBirdWindowsCL.exe"

::goto :eof

:: Make msi
echo Creating the .msi...
jpackage ^
  --type msi ^
  --app-version %ver% ^
  --copyright "BirdBrain Technologies LLC" ^
  --description "BlueBird Connector from BirdBrain Technologies" ^
  --name "BlueBird Connector" ^
  --vendor "BirdBrain Technologies" ^
  --app-image "BlueBird Connector" ^
  --win-menu ^
  --win-shortcut

echo Signing the .msi...
signtool sign /fd SHA256 /f BIRDBRAIN.pfx /p %1 "BlueBird Connector-%ver%.msi"

:: Cleanup
echo Cleaning up...
rmdir %filesdir% /S /Q
rmdir "BlueBird Connector" /S /Q
echo DONE

:: NOTES:
:: You can add additional launchers for debugging or for tts, but the installer
:: will then install these with separate icons on the desktop.
:: Example:
:: first define variables
:: SET DEBUGOPTIONSFILE=debug.properties
:: SET TTSOPTIONSFILE=tts.properties
:: Write the properties files:
:: echo win-console=true> %DEBUGOPTIONSFILE%
:: echo arguments=tts> %TTSOPTIONSFILE%
:: echo win-console=true>> %TTSOPTIONSFILE%
:: Then, add the following options when making the app-image:
:: --add-launcher "BlueBird Connector Debug"=%DEBUGOPTIONSFILE% ^
:: --add-launcher "BlueBird Connector TTS"=%TTSOPTIONSFILE% ^
:: Cleanup:
:: del %DEBUGOPTIONSFILE%
:: del %TTSOPTIONSFILE%
::
:: TTS can be used by adding the command line argument 'tts' and optionally the
:: additional argument 'autocalib' to autocalibrate the compass.
::
:: If you need to find the alias of a new signing certificate, the command is
:: keytool -list -v -storetype pkcs12 -keystore BIRDBRAIN.pfx
::
:: To verify that the jar is signed, you can run
:: jarsigner -verify "BlueBird Connector\app\BlueBirdConnector.jar"
