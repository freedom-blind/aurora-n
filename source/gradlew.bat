@if "%DEBUG%"=="" @echo off
setlocal

set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
set APP_BASE_NAME=%~n0
set APP_HOME=%DIRNAME%

set JAVA_EXE=C:\Program Files\Android\Android Studio\jbr\bin\java.exe

set CLASSPATH=%APP_HOME%\gradle\wrapper\gradle-wrapper.jar

"%JAVA_EXE%" -Xmx64m -Xms64m %JAVA_OPTS% -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*

:end
endlocal
