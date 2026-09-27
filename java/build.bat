@echo off
REM Compiles all Java sources into java\out  (needs JDK 17+ on PATH)
pushd %~dp0
if exist out rmdir /s /q out
mkdir out
javac -encoding UTF-8 -d out -cp "lib\*" src\sms\common\*.java src\sms\server\*.java src\sms\client\*.java
set RC=%ERRORLEVEL%
popd
if %RC% neq 0 (
    echo Build FAILED
    pause
    exit /b %RC%
)
echo Build OK - classes in java\out
