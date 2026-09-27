@echo off
REM Starts the SMS gateway server. Start this first and keep the window open while sending.
REM
REM Default: Dialog 4G wingle over its web API (no driver needed). Change --user/--pass if you
REM changed the wingle's web login (default admin/admin).
REM
REM For a classic "stick mode" dongle with an AT COM port use instead:
REM   java --enable-native-access=ALL-UNNAMED -cp "java\out;java\lib\*" sms.server.SmsServer --com COM10 --baud 9600 --listen 5555
cd /d %~dp0
if not exist java\out\sms\server\SmsServer.class call java\build.bat
java --enable-native-access=ALL-UNNAMED -cp "java\out;java\lib\*" sms.server.SmsServer --wingle 192.168.0.1 --user admin --pass admin --listen 5555
pause
