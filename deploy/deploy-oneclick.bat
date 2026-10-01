@echo off
chcp 65001 >nul
title AdbControl One-Click Deploy
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0oneclick-deploy.ps1"
