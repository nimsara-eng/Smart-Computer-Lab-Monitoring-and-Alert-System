#!/bin/sh
# Compiles the 4 Java files into the "out" folder.
cd "$(dirname "$0")" || exit 1
mkdir -p out
javac -source 8 -target 8 -Xlint:-options -encoding UTF-8 -d out src/labmonitor/*.java || { echo "BUILD FAILED"; exit 1; }
echo "Build OK"
