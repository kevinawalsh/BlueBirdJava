# BlueBirdJava

This repo is for the new java version of BlueBird Connector. It consists of 2 projects:
1. [BlueBirdConnector](#bbc)
2. [BlueBirdWindowsCL](#bbWinCL)


## <a name="bbc"></a>BlueBirdConnector

This project holds the bulk of the code. It is an IntelliJ project written in java.

The bglib module comes from [bglib](#https://github.com/SINTEF-9012/bglib), though multiple modifications have been made.

## <a name="bbWinCL"></a>BlueBirdWindowsCL

This project creates a command line utility for accessing Windows native bluetooth. It is a Visual Studio project written in C#.

Important build note: You may need to edit the paths of the manual references to Windows.winmd and System.Runtime.WindowsRuntime.dll in BlueBirdWindowsCL.csproj. These references are specific to the build machine.
