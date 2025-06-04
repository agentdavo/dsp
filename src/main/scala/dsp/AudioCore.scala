package dsp

import spinal.core._
import spinal.lib._
import spinal.lib.pipeline._
import spinal.lib.bus.amba3.apb._
import spinal.core.formal._

// Configuration
case class AudioCoreConfig(
  xlen: Int = 64,
  pipelineStages: Int = 6, // Fetch=0, Decode=1, Execute1=2, Execute2=3, Memory=4, Writeback=5 (5 visible stages)
  fixedPointWidth: Int = 32, // Q1.31 default
  fracBits: Int = 31, // Q1.31 for I/O, Q2.30 for coeffs
  coeffWidth: Int = 32, // Q2.30 for coeffs
  coeffFracBits: Int = 30,
  controlFracBits: Int = 16, // Q16.16 for RMS/control
  memDepth: Int = 1024,
  channels: Int = 16,
  qFormats: Seq[Int] = Seq.fill(16)(31), // Per-channel fracBits
  simdLanes: Int = 4,
  enablePluginTemplate: Boolean = false,
  enableAccRegFile: Boolean = true,
  enableMultiRate: Boolean = true,
  enableHdrPath: Boolean = true,
  enableGainShare: Boolean = true,
  enableBankSwitch: Boolean = true,
  enableCoeffLoader: Boolean = true,
  enableFirPartition: Boolean = true,
  enableFastLimiter: Boolean = true,
  enableThermalModel: Boolean = true,
  enableBrownout: Boolean = true,
  enableDualNetwork: Boolean = true,
  enableTelnetDump: Boolean = true,
  enableDebug: Boolean = true,
  enableFormal: Boolean = true,
  enablePowerOpt: Boolean = true,
  enableAssistCore: Boolean = true,
  enableAsicBist: Boolean = true,
  enableOtpCsrs: Boolean = true,
  enablePolyphaseDelay: Boolean = true,
  enablePllDivider: Boolean = true,
  enableHwBreakpoint: Boolean = true
)

// Plugin trait
trait AudioCorePlugin {
  def build(core: AudioCore, timers: FsTimers): Unit
}

// Timer component
class FsTimers(fs1: Int, fs2: Int) extends Component {
  val io = new Bundle {
    val tick = out Bool()
  }
  val counter = Reg(UInt(32 bits)) init(0)
  io.tick := counter === 0
  counter := counter + 1
  assert(counter.getWidth === 32, s"FsTimers counter width mismatch, expected 32")
}

// Main CPU core
class AudioCore(val config: AudioCoreConfig) extends Component {
  import config._

  // Clock domains
  val systemClock = ClockDomain.current
  val bclkDomain = ClockDomain.external("bclk")
  val audio48k = if (enableMultiRate) ClockDomain.external("audio48k") else systemClock
  val audio96k = if (enableMultiRate) ClockDomain.external("audio96k") else systemClock
  val audio192k = if (enableMultiRate) ClockDomain.external("audio192k") else systemClock
  val alwaysOnDomain = if (enableAssistCore || enableDebug) ClockDomain.external("alwaysOn") else systemClock
  val macClock = if (simdLanes <= 2) systemClock else ClockDomain.external("macClk")

  // Instruction types
  object InstrType extends SpinalEnum {
    val R, I, S, B, U, J, AUDIO = newElement()
  }

  // Audio instructions
  object AudioOp extends SpinalEnum {
    val MUL_Q, ADD_Q, SUB_Q, SAT_Q, SHL_Q, SHR_Q,
        MAC_Q, MACACC_Q, VADD_Q, VSUB_Q, VABS_Q, VMAX_Q,
        CIRC_LD, CIRC_ST, LD_COEFF, SWAP_BANK,
        POLYPHASE = newElement()
  }

  // Bundles
  case class InstrCtrl() extends Bundle {
    val pc = UInt(xlen bits)
    val instr = Bits(32 bits)
    val rs1, rs2, rd = UInt(5 bits)
    val imm = SInt(xlen bits)
    val instrType = InstrType()
    val audioOp = AudioOp()
    val isValid = Bool()
  }

  case class StageData() extends Bundle with Stageable {
    val ctrl = InstrCtrl()
    val rs1Data, rs2Data = SInt(xlen bits)
    val result = SInt(xlen bits)
    val simdMask = Bits(simdLanes bits)
  }

  case class CircCtrl() extends Bundle {
    val base = UInt(log2Up(memDepth) bits)
    val len = UInt(log2Up(memDepth) bits)
    val idx = UInt(log2Up(memDepth) bits)
  }

  // I/O
  val io = new Bundle {
    val sampleIn = slave Stream(Vec(SInt(fixedPointWidth bits), channels))
    val sampleOut = master Stream(Vec(SInt(fixedPointWidth bits), channels))
    val axiStream = master Stream(Bits(32 bits))
    val mute = out Bool()
    val compressor = out Bool()
    val wfi = out Bool()
    val exception = out Bool()
    val breakpoint = out Bool()
    val apb = slave(Apb3(16, 32))
  }

  // CSR bus
  val apbFactory = Apb3SlaveFactory(io.apb)
  val csrMap = new Area {
    val thermalTemp = Reg(SInt(16 bits)) init(0)
    val accRegCsr = Reg(SInt(xlen bits)) init(0)
    val fuses = Reg(Bits(32 bits)) init(0)
    val divider = Reg(UInt(8 bits)) init(1)
    val threshold = Reg(SInt(fixedPointWidth bits)) init(1 << 30)
    val bankSel = Reg(Bool()) init(False)
    apbFactory.read(thermalTemp.asBits.resize(32), 0x801)
    apbFactory.read(accRegCsr.asBits.resize(32), 0x800)
    apbFactory.read(fuses, 0x803)
    apbFactory.readAndWrite(divider.asBits.resize(32), 0x804)
    apbFactory.readAndWrite(threshold.asBits.resize(32), 0x805)
    apbFactory.read(bankSel.asBits.resize(32), 0x806)
    apbFactory.write(bankSel, 0x806)
  }

  // Pipeline
  val pipeline = new Pipeline {
    val stages = Array.fill(pipelineStages)(new Stage())
    val STAGE_DATA = Stageable(StageData())
    val fetch = new Area {
      val pc = Reg(UInt(xlen bits)) init(0)
      val instrMem = Mem(Bits(32 bits), 1024)
      val fetchData = InstrCtrl()
      fetchData.pc := pc
      fetchData.instr := instrMem.readSync(pc(xlen-1 downto 2))
      fetchData.isValid := True
      pc := pc + 4
      stages(0)(STAGE_DATA).ctrl := fetchData
    }

    val decode = new Area {
      val stage = stages(1)
      val ctrl = stage(STAGE_DATA).ctrl
      val regFile = Mem(SInt(xlen bits), 32)
      val decoder = new Area {
        val opcode = ctrl.instr(6 downto 0)
        val funct3 = ctrl.instr(14 downto 12)
        val funct7 = ctrl.instr(31 downto 25)
        ctrl.rs1 := ctrl.instr(19 downto 15).asUInt
        ctrl.rs2 := ctrl.instr(24 downto 20).asUInt
        ctrl.rd := ctrl.instr(11 downto 7).asUInt
        ctrl.imm := 0
        ctrl.instrType := InstrType.R
        ctrl.audioOp := AudioOp.MUL_Q

        switch(opcode) {
          is(B"0110011") {
            ctrl.instrType := InstrType.AUDIO
            switch(funct7) {
              is(B"0000000") {
                switch(funct3) {
                  is(B"000") { ctrl.audioOp := AudioOp.MUL_Q }
                  is(B"001") { ctrl.audioOp := AudioOp.ADD_Q }
                  is(B"010") { ctrl.audioOp := AudioOp.SUB_Q }
                  is(B"011") { ctrl.audioOp := AudioOp.SAT_Q }
                  is(B"100") { ctrl.audioOp := AudioOp.SHL_Q }
                  is(B"101") { ctrl.audioOp := AudioOp.SHR_Q }
                }
              }
              is(B"0000010") {
                switch(funct3) {
                  is(B"000") { ctrl.audioOp := AudioOp.MAC_Q }
                  is(B"001") { ctrl.audioOp := AudioOp.MACACC_Q }
                }
              }
              is(B"0000100") {
                switch(funct3) {
                  is(B"000") { ctrl.audioOp := AudioOp.VADD_Q }
                  is(B"001") { ctrl.audioOp := AudioOp.VSUB_Q }
                  is(B"010") { ctrl.audioOp := AudioOp.VABS_Q }
                  is(B"011") { ctrl.audioOp := AudioOp.VMAX_Q }
                }
              }
              is(B"0000110") { ctrl.audioOp := AudioOp.SWAP_BANK }
              is(B"0000111") { ctrl.audioOp := AudioOp.POLYPHASE }
            }
          }
          is(B"0010011") {
            ctrl.instrType := InstrType.I
            ctrl.imm := ctrl.instr(31 downto 20).asSInt.resize(xlen)
            switch(funct3) {
              is(B"100") { ctrl.audioOp := AudioOp.CIRC_LD }
              is(B"101") { ctrl.audioOp := AudioOp.CIRC_ST }
              is(B"110") { ctrl.audioOp := AudioOp.LD_COEFF }
            }
          }
        }
      }
      val stageData = StageData()
      stageData.ctrl := ctrl
      stageData.rs1Data := regFile.readSync(RegNext(ctrl.rs1))
      stageData.rs2Data := regFile.readSync(RegNext(ctrl.rs2))
      stageData.simdMask := Mux(
        ctrl.audioOp === AudioOp.VADD_Q || ctrl.audioOp === AudioOp.VSUB_Q ||
        ctrl.audioOp === AudioOp.VABS_Q || ctrl.audioOp === AudioOp.VMAX_Q,
        B(simdLanes bits, default -> true),
        B(simdLanes bits, default -> false)
      )
      stage(STAGE_DATA) := stageData
    }

    val execute1 = new Area {
      val stage = stages(2)
      val stageData = stage(STAGE_DATA)
      val preResult = SInt(xlen bits)
      preResult := 0
      switch(stageData.ctrl.audioOp) {
        is(AudioOp.MUL_Q, AudioOp.MAC_Q, AudioOp.MACACC_Q) {
          if (simdLanes <= 2) {
            val cycle = Reg(Bool()) init(False)
            cycle := !cycle
            when(cycle) {
              preResult := (stageData.rs1Data * stageData.rs2Data) >> qFormats(stageData.ctrl.imm(7 downto 4).asUInt)
            }
          } else {
            preResult := (stageData.rs1Data * stageData.rs2Data) >> qFormats(stageData.ctrl.imm(7 downto 4).asUInt)
          }
        }
        is(AudioOp.VADD_Q, AudioOp.VSUB_Q, AudioOp.VABS_Q, AudioOp.VMAX_Q) {
          val lanes = simdLanes
          val laneWidth = xlen / lanes
          val res = Vec(SInt(laneWidth bits), lanes)
          for (i <- 0 until lanes) {
            assert((i + 1) * laneWidth - 1 < xlen, s"SIMD array index overflow at lane $i")
            val a = stageData.rs1Data((i + 1) * laneWidth - 1 downto i * laneWidth).asSInt
            val b = stageData.rs2Data((i + 1) * laneWidth - 1 downto i * laneWidth).asSInt
            res(i) := stageData.ctrl.audioOp match {
              case AudioOp.VADD_Q => a + b
              case AudioOp.VSUB_Q => a - b
              case AudioOp.VABS_Q => a.abs()
              case AudioOp.VMAX_Q => Mux(a > b, a, b)
              case _ => S(0, laneWidth bits)
            }
          }
          preResult := res.asBits.asSInt
        }
      }
      stageData.result := preResult
      stage(STAGE_DATA) := stageData
    }

    val execute2 = new Area {
      val stage = stages(3)
      val stageData = stage(STAGE_DATA)
      val accReg = Reg(SInt(xlen bits)) init(0)
      val alu = new Area {
        val result = SInt(xlen bits)
        val maxVal = S((1L << (fixedPointWidth - 1)) - 1, fixedPointWidth bits)
        val minVal = S(-(1L << (fixedPointWidth - 1)), fixedPointWidth bits)

        def sat(value: SInt): SInt = {
          val out = SInt(xlen bits)
          when(value > maxVal) { out := maxVal }
          .elseWhen(value < minVal) { out := minVal }
          .otherwise { out := value }
          assert(out.getWidth === xlen, s"Saturation output width mismatch, expected $xlen")
          out
        }

        result := stageData.result
        switch(stageData.ctrl.instrType) {
          is(InstrType.AUDIO) {
            switch(stageData.ctrl.audioOp) {
              is(AudioOp.MUL_Q, AudioOp.MAC_Q) { result := sat(stageData.result) }
              is(AudioOp.ADD_Q) { result := sat(stageData.rs1Data + stageData.rs2Data) }
              is(AudioOp.SUB_Q) { result := sat(stageData.rs1Data - stageData.rs2Data) }
              is(AudioOp.SAT_Q) { result := sat(stageData.rs1Data) }
              is(AudioOp.SHL_Q) { result := sat(stageData.rs1Data << stageData.rs2Data(5 downto 0)) }
              is(AudioOp.SHR_Q) { result := sat(stageData.rs1Data >> stageData.rs2Data(5 downto 0)) }
              is(AudioOp.MACACC_Q) {
                val acc = stageData.result + accReg
                result := sat(acc)
                accReg := result
                csrMap.accRegCsr := result
                assert(acc.getWidth === xlen, s"MACACC accumulation value mismatch, expected $xlen")
              }
              is(AudioOp.VADD_Q, AudioOp.VSUB_Q, AudioOp.VABS_Q, AudioOp.VMAX_Q) {
                val lanes = simdLanes
                val laneWidth = xlen / lanes
                val res = Vec(SInt(laneWidth bits), lanes)
                for (i <- 0 until lanes) {
                  res(i) := sat(stageData.result((i + 1) * laneWidth - 1 downto i * laneWidth).asSInt)
                }
                result := res.asBits.asSInt
              }
              is(AudioOp.LD_COEFF) { result := coeffMem.readSync(RegNext(stageData.ctrl.imm.asUInt)).resize(xlen) }
              is(AudioOp.POLYPHASE) {
                val coeffs = Vec(SInt(32 bits), 8)
                coeffs.foreach(_ := 0)
                val acc = Reg(SInt(32 bits)) init(0)
                for (i <- 0 until 8) {
                  acc := acc + (stageData.rs1Data * coeffs(i)) >> 31
                }
                result := sat(acc)
                assert(acc.getWidth === 32, s"Polyphase accumulation value mismatch, expected 32")
              }
            }
          }
          is(InstrType.I) { result := stageData.rs1Data + stageData.ctrl.imm }
        }
        stageData.result := result
      }
      stage(STAGE_DATA) := stageData
    }

    val memory = new Area {
      val stage = stages(4)
      val stageData = stage(STAGE_DATA)
      val dataMem = Mem(SInt(xlen bits), memDepth)
      val coeffMem = Mem(SInt(coeffWidth bits), 256)
      val cag = new Area {
        val circCtrl = CircCtrl()
        val idxReg = Reg(UInt(log2Up(memDepth) bits)) init(0)
        circCtrl.base := stageData.ctrl.imm.asUInt
        circCtrl.len := stageData.rs2Data.asUInt
        circCtrl.idx := idxReg
        val addr = circCtrl.base + circCtrl.idx
        when(stageData.ctrl.audioOp === AudioOp.CIRC_LD) {
          stageData.result := dataMem.readSync(RegNext(addr))
          idxReg := (idxReg + 1) % circCtrl.len
          assert(idxReg < circCtrl.len, s"CAG index value exceeds length, idx=$idxReg, len=$circCtrl.len")
        }
        when(stageData.ctrl.audioOp === AudioOp.CIRC_ST) {
          dataMem.write(addr, stageData.rs2Data)
          idxReg := (idxReg + 1) % circCtrl.len
        }
      }
      when(stageData.ctrl.instrType === InstrType.S) {
        dataMem.write(RegNext(stageData.ctrl.imm.asUInt), stageData.rs2Data)
      }
      stage(STAGE_DATA) := stageData
    }

    val writeback = new Area {
      val stage = stages(5)
      val stageData = stage(STAGE_DATA)
      val regFile = decode.regFile
      when(stageData.ctrl.rd =/= 0 && stageData.ctrl.isValid) {
        regFile.write(RegNext(stageData.ctrl.rd), stageData.result)
        when(ClockDomain.current.isResetActive === False) {
          assert(!past(stageData.ctrl.isValid, 1), "Double instruction retire detected")
        }
      }
    }

    stages(0) >> stages(1) >> stages(2) >> stages(3) >> stages(4) >> stages(5)
  }

  // Hazard detection
  val hazardUnit = new Area {
    // Stage references: decode=1, execute1=2, execute2=3, memory=4
    val decodeStage = pipeline.stages(1)
    val execute1Stage = pipeline.stages(2)
    val execute2Stage = pipeline.stages(3)
    val memoryStage = pipeline.stages(4)
    val decodeData = decodeStage(pipeline.STAGE_DATA)
    val execute1Data = execute1Stage(pipeline.STAGE_DATA)
    val execute2Data = execute2Stage(pipeline.STAGE_DATA)
    val memoryData = memoryStage(pipeline.STAGE_DATA)

    when(execute2Data.ctrl.rd =/= 0) {
      when(execute2Data.ctrl.rd === decodeData.ctrl.rs1) {
        decodeData.rs1Data := execute2Data.result
      }
      when(execute2Data.ctrl.rd === decodeData.ctrl.rs2) {
        decodeData.rs2Data := execute2Data.result
      }
    }
    when(memoryData.ctrl.rd =/= 0) {
      when(memoryData.ctrl.rd === decodeData.ctrl.rs1) {
        decodeData.rs1Data := memoryData.result
      }
      when(memoryData.ctrl.rd === decodeData.ctrl.rs2) {
        decodeData.rs2Data := memoryData.result
      }
    }

    val loadUseStall = decodeData.ctrl.audioOp === AudioOp.CIRC_LD &&
                      (execute2Data.ctrl.rd === decodeData.ctrl.rs1 || execute2Data.ctrl.rd === decodeData.ctrl.rs2)
    when(loadUseStall) { pipeline.stages(1).haltIt() }

    when(decodeData.simdMask =/= 0) {
      for (i <- 0 until simdLanes) {
        when(decodeData.simdMask(i)) {
          decodeData.rs1Data((i + 1) * (xlen / simdLanes) - 1 downto i * (xlen / simdLanes)) :=
            execute2Data.result((i + 1) * (xlen / simdLanes) - 1 downto i * (xlen / simdLanes))
        }
      }
    }
  }

  // Plugins (to be populated by LoudspeakerDspTop)
  val plugins: Vector[AudioCorePlugin] = Vector()

  // Default I/O
  io.sampleOut.payload := Vec(Seq.fill(channels)(pipeline.memory.stageData.result.resize(fixedPointWidth)))
  io.sampleOut.valid := io.sampleIn.valid
  io.sampleIn.ready := True
  io.axiStream.payload := 0
  io.axiStream.valid := False
  io.mute := False
  io.compressor := False
  io.wfi := False
  io.exception := False
  io.breakpoint := False
}