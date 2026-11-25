#!/bin/sh
# Demo on one PC: the server, one real agent and one SIMULATED high-CPU agent.
cd "$(dirname "$0")" || exit 1
./build.sh || exit 1
java -cp out labmonitor.LabServer &
sleep 3
java -cp out labmonitor.LabAgent localhost PC-01 &
java -cp out labmonitor.LabAgent localhost PC-02 --simulate &
echo "Demo started. Close the windows (or press Ctrl+C) to stop."
wait
