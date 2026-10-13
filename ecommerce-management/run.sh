#!/usr/bin/env bash
# ===== E-Commerce Management System : compile and run (Linux / macOS) =====
cd "$(dirname "$0")"

if ! ls lib/*.jar >/dev/null 2>&1; then
  echo "[ERROR] No JDBC driver found in lib/. Download ojdbc11.jar into lib/ (see README Step 5)."
  exit 1
fi

mkdir -p out
echo "Compiling..."
javac -d out src/*.java || { echo "[ERROR] Compilation failed. Is the JDK installed?"; exit 1; }

echo "Starting server..."
java -cp "out:lib/*" EcomApp db.properties
