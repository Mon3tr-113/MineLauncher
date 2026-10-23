@echo off
setlocal enabledelayedexpansion
chcp 65001 >nul
cd /d "%~dp0"

echo ============================================================
echo   把 MineLauncher 源码推送到 GitHub
echo ============================================================
echo.
echo 需要先装好 Git（https://git-scm.com/download/win，装完重开一个窗口）。
echo.

where git >nul 2>nul
if errorlevel 1 (
  echo [!] 没有找到 git 命令，请先安装 Git for Windows 并重新打开本窗口。
  pause
  exit /b 1
)

set /p REPO_URL=粘贴仓库地址（形如 https://github.com/你的名字/mclauncher.git）: 
if "!REPO_URL!"=="" (
  echo [!] 仓库地址不能为空。
  pause
  exit /b 1
)

if not exist .git (
  echo [1/4] 初始化本地仓库...
  git init || goto :fail
  git branch -M main
) else (
  echo [1/4] 已有本地仓库，跳过初始化。
)

echo [2/4] 添加文件（build/ dist/ .gradle/ 已在 .gitignore 里排除）...
git add -A || goto :fail

echo [3/4] 提交...
git -c user.name="MineLauncher" -c user.email="mclauncher@example.com" commit -m "feat: MineLauncher 方块启动器 - Java 17 + Swing 实现的 Minecraft 启动器" || echo     （没有新改动需要提交）

echo [4/4] 推送到 !REPO_URL! ...
git remote remove origin >nul 2>nul
git remote add origin "!REPO_URL!" || goto :fail
git push -u origin main || goto :fail

echo.
echo 完成！如果你还想把打包好的启动器放上去：
echo   1) 打开发布页 Pages: !REPO_URL! 去掉 .git 后加 /releases/new
echo   2) 上传 dist\MineLauncher-0.1.0-win-x64.zip 作为 Release 附件
echo      （不建议把 30MB 的 zip 提交进仓库，Release 附件更合适）
echo.
pause
exit /b 0

:fail
echo.
echo [!] 出错了：请检查网络 / 仓库地址 / 是否已在 GitHub 上建好空仓库。
echo     首次推送可能需要登录：Windows 会弹出 Git Credential Manager 让你登录 GitHub。
pause
exit /b 1
