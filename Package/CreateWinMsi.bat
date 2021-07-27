@echo off
if [%1]==[] (
  echo Usage: CreateWinMsi 'signing password'
  goto :eof
)

:: Set the current app version
:: SET VER=3.0
cd ..\BlueBirdConnector
for /f %%i in ('call mvn -q --non-recursive "-Dexec.executable=cmd" "-Dexec.args=/C echo ${project.version}" "org.codehaus.mojo:exec-maven-plugin:1.3.1:exec"') do set VER=%%i
echo Setting %ver% as the project version
cd ..\Package

:: Set the path to your copy of the javafx jmods
SET JMODS="C:\Program Files\javafx-jmods-16"

SET FILESDIR=temp
SET SUPPORTDIR=supportFiles

echo Copying the jar file...
mkdir %filesdir%
copy ..\BlueBirdConnector\out\artifacts\BlueBirdConnector_jar\BlueBirdConnector.jar %filesdir%\BlueBirdConnector.jar

:: Make the app image separately so that the command line application can be copied in before the msi is made.
echo Creating the app-image...
jpackage ^
  --type app-image ^
  --app-version %ver% ^
  --copyright "BirdBrain Technologies LLC" ^
  --description "BlueBird Connector from BirdBrain Technologies" ^
  --name "BlueBird Connector" ^
  --vendor "BirdBrain Technologies" ^
  --input %filesdir% ^
  --icon %SUPPORTDIR%\hummingbirdlogo.ico ^
  --module-path %jmods% ^
  --add-modules javafx.controls,javafx.web ^
  --main-jar BlueBirdConnector.jar ^
  --verbose ^
  --main-class com.birdbraintechnologies.bluebirdconnector.BlueBirdConnector
:: Additional useful flags:
:: * Have the app run in a console window (good for debugging) with
::  --win-console ^

:: copy in the windows native bluetooth utility. This needs to be in the same folder as the launcher.
echo Copying the command line utility...
copy ..\BlueBirdWindowsCL\bin\Release\BlueBirdWindowsCL.exe "BlueBird Connector\BlueBirdWindowsCL.exe"
mkdir "BlueBird Connector\BluetoothDriver"
copy %SUPPORTDIR%\usbserial.cat "BlueBird Connector\BluetoothDriver\usbserial.cat"
copy %SUPPORTDIR%\usbserial.inf "BlueBird Connector\BluetoothDriver\usbserial.inf"

echo Signing BlueBirdConnector.jar, BlueBird Connector.exe, and BlueBirdWindowsCL.exe...
jarsigner -tsa http://timestamp.digicert.com -storetype pkcs12 -keystore BIRDBRAIN.pfx -storepass %1 "BlueBird Connector\app\BlueBirdConnector.jar" 73cfaf53eaee4153b44e02ca7b2a7e76
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
  --verbose ^
  --resource-dir overrides ^
  --win-menu ^
  --win-shortcut
:: Additional useful flags:
:: * see copies of the temp files being used with
::  --temp tempFiles ^
::

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
::
:: To capture a log of msi installation, you can install from command line:
:: msiexec /i "BlueBird Connector-3.0.msi" /L*V msiLog.log
