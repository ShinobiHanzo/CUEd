@echo off
REM CUEd companion, Windows. First run makes a venv and installs spotdl.
setlocal
cd /d "%~dp0"
where py >nul 2>nul && (set PY=py -3) || (set PY=python)
if not exist .venv (
  %PY% -m venv .venv || (echo Python 3.9+ is required: https://www.python.org/downloads/ & pause & exit /b 1)
  .venv\Scripts\python -m pip install --upgrade pip >nul
  .venv\Scripts\python -m pip install -r requirements.txt
)
.venv\Scripts\python server.py %*
pause
