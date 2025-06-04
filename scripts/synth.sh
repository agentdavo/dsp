#!/bin/bash
set -e
echo "Running Yosys synthesis estimation..."
sbt "runMain dsp.LoudspeakerDspVerilog"
yosys -p "read_verilog LoudspeakerDspTop.v; synth_xilinx -top LoudspeakerDspTop -edif LoudspeakerDspTop.edif" > synth.log
echo "Synthesis complete, resource usage:"
grep -E "Number of LUTs|BRAM|DSP" synth.log
echo "Timing report:"
grep -E "Timing estimate" synth.log