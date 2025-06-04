# agents.md

## Scope
This document outlines the rules and best practices for an AI-based "hardware co-pilot" to maintain and enhance the `dsp` project, a fixed-point, 5-stage pipeline AudioCore implemented in SpinalHDL 1.12.0 for loudspeaker digital signal processing (DSP) targeting Xilinx Artix-7 FPGAs (e.g., xc7a200t). The goal is to produce synthesizable, formally verified, and simulation-tested SpinalHDL code that compiles on the first attempt (`sbt test:compile`) and adheres to strict type safety, modularity, and performance requirements (250 MHz, ~18k LUTs, 62 BRAMs, 24 DSPs). The AI must iterate on the codebase, located in the current file structure, to deliver a production-ready solution while complying with the project's architectural constraints and verification workflows.

## Project Context
- **Purpose**: The `loudspeaker-dsp` project implements a custom RISC-V-based AudioCore for real-time audio processing, supporting fixed-point arithmetic (Q1.31 for I/O, Q2.30 for coefficients), SIMD operations (4 lanes), and a 5-stage pipeline (Fetch, Decode, Execute, Memory, Writeback) with internal E1/E2 sub-stages in Execute for timing closure.
- **Target**: Xilinx Artix-7 (xc7a200t), 250 MHz, deterministic latency, ~1.67 W power consumption (1.35 W dynamic, 0.32 W static).
- **Toolchain**: SpinalHDL 1.12.0 Scala 2.13.14, SBT 1.10.2, Yosys for synthesis, SymbiYosys for formal verification.
- **Features**: Modular plugins (e.g., AccRegFile, MultiRate, FastLimiter), peripherals (I²S, DMA), and formal properties (e.g., `¬doubleRetire`, `MAC_bound`).
- **Constraints**: Strict type safety, no synthesis warnings, formal verification depth-8 (escalate beyond 8), simulation with 1-second pink noise regression.

## File Structure
The project follows a modular directory structure, ensuring separation of RTL, plugins, peripherals, tests, formal harnesses, and scripts. All source files are under `src/` unless specified.

```
loudspeaker-dsp/
├─ build.sbt
├─ project/
│  ├─ build.properties
│  ├─ plugins.sbt
│  └─ .scalafmt.conf
├─ src/
│  ├─ main/
│  │   └─ scala/
│  │       └─ dsp/                # Core RTL and generators
│  │            ├─ AudioCore.scala
│  │            ├─ LoudspeakerDspTop.scala
│  │            ├─ plugins/       # Plugin implementations
│  │            │    └─ PluginTemplate.scala
│  │            └─ peripherals/   # Peripheral modules
│  │                 └─ I2sStub.scala
│  ├─ test/
│  │   └─ scala/                  # SpinalSim and ScalaTest
│  │        └─ PinkNoiseEnd2End.scala
│  ├─ formal/
│  │   └─ scala/                  # SymbiYosys harnesses
│  │        └─ AudioCoreFormal.scala
├─ scripts/                       # Automation scripts
│   ├─ prove.sh
│   ├─ synth.sh
│   └─ AudioCore.sby
└─ .github/workflows/ci.yml       # GitHub Actions CI
```

### Key Files
- **`build.sbt`**: Configures SBT 1.10.2, SpinalHDL 1.9.5, Scala 2.13.14, and dependencies. Enables `-Xfatal-warnings` and includes `src/formal/scala` in compilation.
- **`AudioCore.scala`**: Defines `AudioCoreConfig`, `AudioCorePlugin` trait, `FsTimers`, and the 5-stage `AudioCore` with E1/E2 sub-stages (`pipelineStages = 6`).
- **`LoudspeakerDspTop.scala`**: Top-level module integrating `AudioCore`, peripherals, and plugins; generates Verilog via `LoudspeakerDspVerilog`.
- **`PluginTemplate.scala`**: Contains `PluginTemplate` and all implemented plugins (e.g., `AccRegFilePlugin`, `MultiRatePlugin`).
- **`I2sStub.scala`**: Defines peripherals (e.g., `I2sReceiver`, `I2sTransmitter`, `SimpleDma`).
- **`PinkNoiseEnd2End.scala`**: ScalaTest for 1-second pink noise regression.
- **`AudioCoreFormal.scala`**: SymbiYosys harness with properties (`¬doubleRetire`, `MAC_bound`, `CAG_wrap`).
- **`prove.sh`**: Runs formal verification with `AudioCore.sby` (depth-8).
- **`synth.sh`**: Performs Yosys synthesis estimation.
- **`AudioCore.sby`**: SymbiYosys configuration for BMC with Yices solver.

## Golden Rules — Compile First
1. **Always Compile First**: Run `sbt clean` followed by `sbt test:compile` locally before submitting changes. If compilation fails, do not proceed.
2. **Pinned Toolchain**:
   - SpinalHDL: 1.12.0
   - Scala: 2.13.14
   - SBT: 1.10.2
   - Yosys/SymbiYosys: Latest stable
   - Use exact versions to avoid compatibility issues.
3. **No Synthesis Warnings**: Ensure zero synthesis warnings in `synth.sh` output. Escalate timing slack < -0.2 ns or fan-out > 64.
4. **Formal Verification**: Pass SymbiYosys depth-8 (`prove.sh`). Escalate failures beyond depth 8 to humans.
5. **Simulation**: Pass `PinkNoiseEnd2End` test (`sbt test`) for 1-second pink noise regression.
6. **Strict Type Safety**: Adhere to SpinalHDL type discipline (e.g., `Bool` not `Boolean`, `SInt` not `Int`). Use `spinal.core.assert` for assertions.

## Language & Library Checklist
Ensure strict compliance with SpinalHDL 1.9.5 conventions to avoid compilation errors.

| Topic                | Mandatory Pattern                                      | Never Do                                      |
|----------------------|-------------------------------------------------------|----------------------------------------------|
| Conditionals         | `Mux(cond: Bool, a, b)`                               | `cond ? a : b` (ternary)                     |
| Loops                | `for(i <- 0 until N) { ... }`                        | `for(i = 0 to N)`                            |
| Vectors              | `Vec(SInt(32 bits), N)`                              | `Vec[SInt](...)`, extra/missing parentheses  |
| Clock Domains        | `ClockDomain.external("name")`                       | `ClockDomain("name")`                        |
| Memories             | `Mem[SInt](N).readSync(RegNext(addr))`               | Combinational `readSync`, `writeSync`        |
| Pipeline Stages      | `Stageable[T]`, `stage(Stageable)`, `>>` connections | `setInput`, `setOutput`, `getOutput`         |
| Streams              | `StreamArbiterFactory().roundRobin.on(Seq(...))`     | Ad-hoc `Mux` for streams                     |
| Clock Gating         | `ClockEnableArea(cond: Bool) { ... }`                | Modify parent `ClockDomain`                  |
| Literals             | `S(0, 32 bits)`, `U(0, 32 bits)`                     | `0.S(32 bits)`, raw integers                 |
| Assertions           | `assert(cond: Bool, "message")`                      | `assertSim`, Scala `assert`                  |
| State Machines       | `new StateMachine { val S = new State ... }`         | Manual state encoding                        |
| Memory Technology    | `setTechnology(MemTechnologyAuto)`                   | `tech = "auto"` (string)                     |

## Pipeline Contract
- **Visible Stages**: 5 stages (Fetch, Decode, Execute, Memory, Writeback), externally visible as a 5-stage pipeline.
- **Internal Sub-Stages**: Execute splits into E1 (stage 2: raw computation) and E2 (stage 3: saturation/accumulation), setting `pipelineStages = 6`. Escalate any pipeline depth changes.
- **Stage Indices**:
  - Fetch: 0
  - Decode: 1
  - Execute1: 2
  - Execute2: 3
  - Memory: 4
  - Writeback: 5
- **Data Flow**: Use `Stageable[StageData]` for pipeline data, connected via `>>`. Access data with `stage(STAGE_DATA)`.
- **Hazard Handling**: Implement RS1/RS2 forwarding and load-use stalls in `hazardUnit`. Ensure SIMD lane-wise forwarding.
- **Plugin Integration**: Plugins affecting Execute (e.g., `AccRegFilePlugin`) must use `whenIsActive` and access `pipeline.execute1`/`execute2`.

## Clock-Domain Discipline
1. **External Clocks**:
   ```scala
   val clk = ClockDomain.external("name", config = ClockDomainConfig())
   ```
   - Defined clocks: `bclk`, `audio48k`, `audio96k`, `audio192k`, `macClk`, `alwaysOn`.
   - Use `ClockingArea(clk) { ... }` for logic.
2. **Crossings**: Use `StreamFifoCC(dataType, depth, pushClock, popClock)` or `FlowCC` for all clock domain crossings. Never use raw registers.
3. **Gating**: Apply `ClockEnableArea(cond: Bool)` for power optimization (e.g., `PowerOptPlugin`). Separate gating for Execute1 and Execute2 to limit fan-out < 64.
4. **Debug/Assist**: Place debug and assist cores in `alwaysOn` domain.

## Memory Usage
| Need                | Primitive                     | Notes                              |
|---------------------|-------------------------------|------------------------------------|
| Coefficient/FIR     | `Mem[SInt](N).readSync(RegNext(addr))` | Use registered addresses.         |
| Trace RAM           | `Mem[SInt](N).write(addr, data, enable)` | Depth ≤ 4096, write-enabled.     |
| Small Registers     | `Vec(Reg(SInt(32 bits)), N)`  | Avoid LUT-RAM inference.           |

- Set `MemTechnologyAuto` for BRAM inference:
  ```scala
  mem.setTechnology(MemTechnologyAuto)
  ```

## Pull-Request Workflow
1. **Branch**: Create a feature branch: `git checkout -b dev/<feature>`.
2. **Patch Size**: Limit changes to <400 lines or 1 new file. Escalate larger changes.
3. **Compile**: Run `sbt clean` and `sbt test:compile`. Fix all errors before proceeding.
4. **Test**: Run `sbt test` to pass `PinkNoiseEnd2End`.
5. **Formal**: Run `scripts/prove.sh` to pass SymbiYosys depth-8.
6. **Synthesis**: Run `scripts/synth.sh` to check resources and timing. Escalate if LUT/BRAM/DSP delta > 10%.
7. **Metrics**: Update `metrics.json` with resource usage and feature list.
8. **CHANGELOG**: Add a stanza in `CHANGELOG.md`:
   ```
   YYYY-MM-DD: <Description of changes>. Passed sbt compile, SymbiYosys depth-8, and 1s pink noise test. Resource usage: <LUTs>, <BRAMs>, <DSPs>.
   ```
9. **PR**: Create a draft PR with `CHANGELOG` entry. Mark `needs-human-review` for pipeline changes, new clocks, or resource deltas > 10%.
10. **CI**: Ensure `.github/workflows/ci.yml` passes (`sbt test:compile`, `sbt test`, `prove.sh`).

If any step fails, iterate until resolved. Only declare "Ready for merge" when all steps pass.

## Formal & Simulation Obligations
| Layer       | Required Artifact                                      |
|-------------|-------------------------------------------------------|
| ALU/MAC     | ScalaTest with edge cases (±2³¹-1) in `PinkNoiseEnd2End` |
| Pipeline    | SymbiYosys: `¬doubleRetire`, `MAC_bound`, `CAG_wrap`  |
| CDC         | `CdclibCheck` for each `StreamFifoCC` (manual check)  |
| End-to-End  | Pink noise regression (1s, 48kHz) in `PinkNoiseEnd2End` |

- **New Plugins**: Add at least one `assert` per plugin (e.g., width checks, state transitions).
- **Formal Depth**: Depth-8 mandatory; escalate failures beyond 8 to humans.

## Plugin Template
```scala
package dsp.plugins

import spinal.core._
import dsp.{AudioCore, FsTimers}

class MyPlugin extends AudioCorePlugin {
  def build(core: AudioCore, timers: FsTimers): Unit = {
    import core._
    import core.config._
    when(Bool(enableMyPlugin)) {
      val area = new ClockingArea(systemClock) {
        // Logic here
      }
      assert(True, "MyPlugin invariant")
    }
  }
}
```

- Never access another plugin’s internals; use named buses or CSRs.
- Add `enableMyPlugin` to `AudioCoreConfig` and instantiate in `LoudspeakerDspTop`.

## Common Pitfalls to Avoid
1. **Type Mismatches**:
   - Use `spinal.core.Bool` not `scala.Boolean`.
   - Ensure `SInt`/`UInt` for arithmetic, not `Int`.
   - Correct: `Vec(SInt(32 bits), N)`, not `Vector` or malformed generics.
2. **Pipeline Errors**:
   - Avoid `setInput`/`setOutput`; use `Stageable` and `>>`.
   - Check stage indices (0 to 5) to prevent array overflows.
3. **Memory Access**:
   - Always register `readSync` addresses with `RegNext`.
   - Use `write(addr, data)` not `writeSync`.
4. **Assertions**:
   - Use `assert(cond: Bool, "message")` in RTL; avoid `assertSim` or Scala `assert`.
   - Guard reset-cycle assertions with `isResetActive === False`.
5. **Clock Crossings**:
   - Use `StreamFifoCC` for all crossings; verify with `CdclibCheck`.
6. **Synthesis**:
   - Check `synth.sh` for fan-out > 64 or negative slack < -0.2 ns.
   - Ensure `MemTechnologyAuto` for BRAM inference.

## Escalation Triggers
Escalate to humans for:
- Pipeline depth changes (beyond E1/E2 split).
- New `ClockDomain` definitions.
- Resource delta > 10% (LUTs, BRAMs, DSPs).
- Formal proof failures at depth > 8.
- Timing slack < -0.2 ns or fan-out > 64.
- New plugins requiring >3k LUTs (e.g., SHA-256 core).

## Example "Good Patch" Checklist
- Adds ≤400 lines or 1 new file.
- Passes `sbt test:compile` with `-Xfatal-warnings`.
- No `???` or `TODO` in RTL (use comments for TODOs).
- New logic in a plugin with at least one `assert`.
- Passes `sbt test` (pink noise regression).
- Passes `prove.sh` (SymbiYosys depth-8).
- Updates `metrics.json` with resource usage.
- Includes `CHANGELOG` stanza.
- CI passes (`.github/workflows/ci.yml`).

## Build and Verification Workflow
1. **Compile**:
   ```bash
   sbt clean
   sbt test:compile
   ```
2. **Test**:
   ```bash
   sbt test
   ```
3. **Formal**:
   ```bash
   ./scripts/prove.sh
   ```
4. **Synthesis**:
   ```bash
   ./scripts/synth.sh
   ```
5. **Verilog**:
   ```bash
   sbt "runMain dsp.LoudspeakerDspVerilog"
   ```
6. **CI**: Push to branch and verify GitHub Actions.

## Metrics Update
Update `metrics.json` after each change:
```json
{
  "date": "2025-06-04",
  "gitSha": "<commit-sha>",
  "features": ["simd4", "accRegFile", "multiRate", ...],
  "fpga": {
    "device": "xc7a200t",
    "fMax": "250 MHz",
    "sliceLUTs": 18245,
    "sliceRegs": 12477,
    "bram18": 62,
    "dsp": 24
  },
  "power": {
    "dynamic": "1.35 W",
    "static": "0.32 W"
  }
}
```

## SpinalHDL Best Practices
- **Type Safety**: Always specify bit widths (e.g., `SInt(32 bits)`). Use `resize` explicitly.
- **Modularity**: Encapsulate logic in plugins or peripherals. Avoid monolithic changes.
- **Assertions**: Add width and invariant checks (e.g., `assert(signal.getWidth === N)`).
- **Comments**: Document stage indices, clock domains, and plugin purposes.
- **Simulation**: Extend `PinkNoiseEnd2End` for new features, maintaining 1-second runtime.
- **Formal**: Add new properties for plugins (e.g., state machine transitions).

## Final Working Solution Goals
- **Compilation**: Zero errors/warnings in `sbt test:compile`.
- **Simulation**: 1-second pink noise regression passes with >80% coverage (via `scoverage`).
- **Formal**: All properties pass at depth-8; escalate deeper failures.
- **Synthesis**: Meets 250 MHz, <20k LUTs, <70 BRAMs, <30 DSPs, no timing violations.
- **Modularity**: All features as plugins, configurable via `AudioCoreConfig`.
- **Documentation**: Clear `CHANGELOG` and comments for maintainability.

## Remember
Correct, synthesizable, and formally verified SpinalHDL is the priority. Optimize for clarity and compliance before adding new features. Iterate with small, verifiable changes to achieve a final working solution.