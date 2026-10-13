@echo off
REM ===== E-Commerce Management System : compile and run (Windows) =====
cd /d "%~dp0"

if not exist lib\*.jar (
  echo [ERROR] No JDBC driver found in the lib folder.
  echo         Copy ojdbc11.jar / ojdbc8.jar / ojdbc6.jar into lib\  - see README Step 5.
  pause
  exit /b 1
)

if not exist out mkdir out
echo Compiling...
javac -d out src\*.java
if errorlevel 1 (
  echo [ERROR] Compilation failed. Is the JDK installed and on PATH?
  pause
  exit /b 1
)

echo Starting server...
java -cp "out;lib\*" EcomApp db.properties
pause
