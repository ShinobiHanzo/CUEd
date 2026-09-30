#!/usr/bin/env bash
# CUEd companion, Linux/macOS. First run makes a venv and installs spotdl.
set -e
cd "$(dirname "$0")"
PY=python3
command -v $PY >/dev/null || { echo "python3 is required (3.9+)"; exit 1; }
if [ ! -d .venv ]; then
  $PY -m venv .venv
  .venv/bin/python -m pip install --upgrade pip >/dev/null
  .venv/bin/python -m pip install -r requirements.txt
fi
exec .venv/bin/python server.py "$@"
