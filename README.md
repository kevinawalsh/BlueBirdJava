# BlueBirdJava - kevinawalsh fork

This is a fork of the Bird Brain "BlueBirdjava" project, adding linux bluetooth
support for working with finch and hummingbird robots. Specifically, this fork
provides a linux version of the BlueBirdConnector app.

## How-To

To compile and package the linux bluebirdconnector app requires a linux
machine, The resulting package is a standalone app that can deployed on other
linux machines.

The compilation and packaging process is fussy. Some notable caveats:

* maven (mvn) is used for compilation, using a pom.xml file.

* The final packaging step uses jlink (via the javafx-maven-plugin), to produce
  a self-contained custom Java runtime + launcher, not a portable jar. 

* jlink refuses non-modular dependencies, but two of the project dependencies
  (freetts and slf4j-nop) aren't available as proper java modules.  Instead of
  the published dependencies, we use two custom "-modular" replacement jars,
  which we have hand-built (see libs/freetts-unpacked/ and
  libs/slf4j-nop-unpacked/). Each has a build.sh script that unpacks the
  original artifact (from a local cache ~/.m2 directory), compiles a
  hand-written module-info.java into it, rejars it, and mvn
  install:install-files it under a -modular version that our overall pom.xml
  depends on.

* jlink output is platform-native, containing copies of the JDK modules for
  whichever OS/JVM where maven was run. So the compile + packaging must be done
  on a linux machine (presumably with similar enough architecture to the
  eventual installation targets).

* The JDK version used to run maven matters. jlink bakes in modules from
  whatever JDK invokes it. Our pom.xml uses "--release 11", an old java, because
  the project relies on JavaFX 16 and a fairly old javafx-maven-plugin (0.0.6).
  So compilation + packaging needs to happen on a machine that can compile
  against the ancient java release 11.

# Step-by-step linux compilation, packaging, and install instructions

1. One-time setup per dev machine (make sure the original non-modular dependency
   jars are in your local repo cache first):

    mvn dependency:get -Dartifact=net.sf.sociaal:freetts:1.2.2
    mvn dependency:get -Dartifact=org.slf4j:slf4j-nop:2.0.0-alpha1

   Then build + install the two custom modular wrapper jars on the dev machine:

    cd libs/freetts-unpacked && ./build.sh && cd ../..
    cd libs/slf4j-nop-unpacked && ./build.sh && cd ../..

  This only needs to be redone if the cache in ~/.m2 is wiped, or if starting
  out on a new dev machine.

2. Compile + package:

    mvn clean package

   This compiles and builds a shaded/uber jar (from maven-shade-plugin, useful
   for quick local testing via java -jar target/BlueBirdConnector-3.1.jar), and
   then runs jlink (from javafx-maven-plugin) to create the actual deliverable.

3. Output: the self-contained app image lands at target/BlueBirdConnector/ (per
   `<jlinkImageName>`), with the launcher at `target/BlueBirdConnector/bin/launcher`.

4. "Install" to target machines, e.g. a lab machine where the robots will be
   used (note: there's no deploy script for this, it is done by hand).

   Copy or rsync the entire `target/BlueBirdConnector` folder to the target
   linux machine. Change the permissions as needed so it is accessible for users
   to run. This folder is self-contained, with a complete JRE and all native
   libraries and jars needed to run the app.

   Copy the `bluebirdconnector.sh` file to a location in PATH on the target
   linux machine, and edit `BB_DIR` within that script. Make the script
   executable and accessible for users to run. Also remove the `.sh` extension
   if desired. This provides a user-friendly wrapper for launching the app.

# BlueBirdJava

This repo is for the new java version of BlueBird Connector. It consists of 2 projects:
1. [BlueBirdConnector](#bbc)
2. [BlueBirdWindowsCL](#bbWinCL)

#### Running the application:
Create a .jar from BlueBirdConnector and an .exe from BlueBirdWindowsCL. Copy both into the same directory and run the following command (modifiying the path for the location of your javafx libraries):
```
java -p "C:\Program Files\javafx-sdk-16\lib" --add-modules javafx.controls,javafx.web -jar BlueBirdConnector.jar
```

#### Packaging

To create a Windows msi:
* You will need to have [wix](https://wixtoolset.org/) installed.
* You will need to download the javafx [jmods](https://gluonhq.com/products/javafx/).
* Create the jar artifact from the [BlueBirdConnector](#bbc) project.
* If modifications have been made to the [BlueBirdWindowsCL](#bbWinCL) project, build a new exe.
* In the Package directory, run ```CreateWinMsi.bat```. You will need to edit the variables on the first few lines for your specific situation.

#### Linux useage

No access to native ble is implemented on linux, and therefore the use of a bluegiga ble dongle is required. Communication with the dongle uses jSerialComm and the user may need to gain permission to use the serial ports by running the following commands in a terminal:
```
sudo usermod -a -G uucp username
sudo usermod -a -G dialout username
sudo usermod -a -G lock username
sudo usermod -a -G tty username
```
Where 'username' is the current username. See https://fazecast.github.io/jSerialComm/ for more information.


## <a name="bbc"></a>BlueBirdConnector

This project holds the bulk of the code. It is an IntelliJ project written in java.

The bglib module comes from [bglib](#https://github.com/SINTEF-9012/bglib), though multiple modifications have been made.

## <a name="bbWinCL"></a>BlueBirdWindowsCL

This project creates a command line utility for accessing Windows native bluetooth. It is a Visual Studio project written in C#.

Important build note: You may need to edit the paths of the manual references to Windows.winmd and System.Runtime.WindowsRuntime.dll in BlueBirdWindowsCL.csproj. These references are specific to the build machine.
