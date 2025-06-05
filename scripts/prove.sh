#!/bin/bash
set -e
echo "Running SymbiYosys formal verification..."
sbt "runMain spinal.core.formal.FormalMain dsp.AudioCoreFormal"
yosys -p "read_verilog -formal AudioCore.v; prep -top AudioCore; write_smt2 -wires AudioCore.smt2"
sby -f AudioCore.sby --depth 8
echo "Formal verification passed with depth 8"