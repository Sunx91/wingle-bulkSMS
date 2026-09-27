@echo off
REM Sends every unsent line of sms_list.txt. Each line is:  NUMBER MESSAGE TEXT WITH SPACES
REM A line with only a number sends the text of sms_template.txt.
REM Double-click to use sms_list.txt, or drag and drop another list TXT onto this .bat.
REM Results are appended to the list; a summary goes to reports\ and a copy of the list to backups\.
REM run_server.bat must already be running.
cd /d %~dp0
if not exist java\out\sms\client\SmsClient.class call java\build.bat
if "%~1"=="" (
    java -cp "java\out;java\lib\*" sms.client.SmsClient sms_list.txt --host localhost --port 5555
) else (
    java -cp "java\out;java\lib\*" sms.client.SmsClient "%~1" --host localhost --port 5555
)
pause
