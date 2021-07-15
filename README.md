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
* In the Package directory, run ```CreateWinMsi.bat```. You will need to edit the variables on the first few lines for your specific situation.



## <a name="bbc"></a>BlueBirdConnector

This project holds the bulk of the code. It is an IntelliJ project written in java.

The bglib module comes from [bglib](#https://github.com/SINTEF-9012/bglib), though multiple modifications have been made.

## <a name="bbWinCL"></a>BlueBirdWindowsCL

This project creates a command line utility for accessing Windows native bluetooth. It is a Visual Studio project written in C#.

Important build note: You may need to edit the paths of the manual references to Windows.winmd and System.Runtime.WindowsRuntime.dll in BlueBirdWindowsCL.csproj. These references are specific to the build machine.
