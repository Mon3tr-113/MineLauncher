@echo off
setlocal
cd /d "%~dp0"

echo 正在构建 Minecraft 启动器 exe（jlink 精简 JRE + jpackage）...
echo 这需要 JDK 17+，首次会下载 Gradle 依赖，请耐心等待。
echo.

call gradlew.bat exe
if not errorlevel 1 goto :done

echo.
echo 第一次打包失败（jpackage 偶尔会失败），2 秒后重试一次...
timeout /t 2 /nobreak >nul
call gradlew.bat exe
if errorlevel 1 goto :fail

:done
echo.
echo 完成！exe 位于 dist\MineLauncher\MineLauncher.exe
echo 注意：整个 dist\MineLauncher 目录都要保留（exe 依赖同目录的 runtime 与 app）。
pause
exit /b 0

:fail
echo 构建失败，请检查上面的错误信息。
echo 若反复失败，可先关闭占用 dist 目录的程序（或杀毒软件）再重试。
pause
exit /b 1
