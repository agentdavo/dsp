# Loudspeaker DSP
## Build Instructions
- Compile: `sbt test:compile`
- Run tests: `sbt test`
- Generate Verilog: `sbt "runMain dsp.LoudspeakerDspVerilog"`
- Formal verification: `./scripts/prove.sh`
- Synthesis estimation: `./scripts/synth.sh`
## CI
- GitHub Actions: `.github/workflows/ci.yml`
