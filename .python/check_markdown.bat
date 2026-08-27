@echo off
setlocal
python "%~dp0generate_markdown.py" --check
exit /b %errorlevel%
