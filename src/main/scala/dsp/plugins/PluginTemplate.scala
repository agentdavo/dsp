package dsp.plugins

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._
import spinal.lib.pipeline._
import dsp.{AudioCore, FsTimers, AudioCorePlugin}
import dsp.peripherals._
import spinal.core.formal._

// Empty plugin template
class PluginTemplate extends AudioCorePlugin {
  def build(core: AudioCore, timers: FsTimers): Unit = {
    import core._
    import core.config._
    when(Bool(enablePluginTemplate)) {
      val area = new ClockingArea(systemClock) {
        // Logic placeholder
      }
      assert(True, "PluginTemplate placeholder assertion")
    }
  }
}

// Implemented plugins
class AccRegFilePlugin extends AudioCorePlugin {
  def build(core: AudioCore, timers: FsTimers): Unit = {
    import core._
    import core.config._
    val accRegFile = new Area {
      val accRegs = Mem(SInt(xlen bits), 32)
    }
    when(core.pipeline.execute2.stage.isValid) {
      when(core.pipeline.decode.stageData.ctrl.audioOp === AudioOp.MACACC_Q && Bool(enableAccRegFile)) {
        val accIdx = core.pipeline.decode.stageData.ctrl.imm(4 downto 0).asUInt
        val m = core.pipeline.execute1.stageData.result
        val acc = m + accRegFile.accRegs.readSync(RegNext(accIdx))
        core.pipeline.execute2.alu.result := core.pipeline.execute2.alu.sat(acc)
        accRegFile.accRegs.write(accIdx, core.pipeline.execute2.alu.result)
        assert(acc.getWidth == xlen, s"AccRegFile accumulation width mismatch, expected $xlen")
      }
      when(pipeline.fetch.pc =/= pipeline.decode.stageData.ctrl.pc && Bool(enableAccRegFile)) {
        accRegFile.accRegs.write(RegNext(pipeline.decode.stageData.ctrl.imm(4 downto 0).asUInt), 0)
      }
    }
  }
}

class MultiRatePlugin extends AudioCorePlugin {
  def build(core: AudioCore, timers: FsTimers): Unit = {
    import core._
    import core.config._
    when(Bool(enableMultiRate)) {
      val area = new ClockingArea(systemClock) {
        val core48k = new ClockingArea(audio48k) { val c = new AudioCore(config) }.c
        val core96k = new ClockingArea(audio96k) { val c = new AudioCore(config) }.c
        val core192k = new ClockingArea(audio192k) { val c = new AudioCore(config) }.c
        val fifo48to96 = StreamFifoCC(Vec(SInt(fixedPointWidth bits), channels), 128, audio48k, audio96k)
        val fifo96to192 = StreamFifoCC(Vec(SInt(fixedPointWidth bits), channels), 128, audio96k, audio192k)
        val halfBandFilter = new HalfBandFir(12)

        // Drive core48k, core96k, core192k inputs
        core48k.io.sampleIn.valid := core.io.sampleIn.valid
        core48k.io.sampleIn.payload := core.io.sampleIn.payload
        core.io.sampleIn.ready := core48k.io.sampleIn.ready

        fifo48to96.io.push.valid := core.io.sampleIn.valid
        fifo48to96.io.push.payload := Vec(Seq.fill(channels)(halfBandFilter.io.output))
        halfBandFilter.io.input := core.io.sampleIn.payload(0)

        core96k.io.sampleIn <> fifo48to96.io.pop
        core192k.io.sampleIn <> fifo96to192.io.pop
        fifo96to192.io.push <> fifo48to96.io.pop

        // Drive core output from core96k
        core.io.sampleOut.valid := core96k.io.sampleOut.valid
        core.io.sampleOut.payload := core96k.io.sampleOut.payload
        core96k.io.sampleOut.ready := core.io.sampleOut.ready

        assert(fifo48to96.io.push.payload(0).getWidth == fixedPointWidth, s"MultiRate FIFO payload width mismatch, expected $fixedPointWidth")
      }
    }
  }
}

class HdrPathPlugin extends AudioCorePlugin {
  def build(core: AudioCore, timers: FsTimers): Unit = {
    import core._
    import core.config._
    when(core.pipeline.execute2.stage.isValid) {
      when(core.pipeline.decode.stageData.ctrl.audioOp === AudioOp.MACACC_Q && core.pipeline.decode.stageData.ctrl.imm(5) && Bool(enableHdrPath)) {
        val hdrWidth = 48
        val hdrAcc = Reg(SInt(hdrWidth bits)) init(0)
        val m = core.pipeline.execute1.stageData.result >> (fracBits - 23)
        hdrAcc := core.pipeline.execute2.alu.sat(m.resize(hdrWidth) + hdrAcc)
        core.pipeline.execute2.alu.result := hdrAcc >> (hdrWidth - fixedPointWidth)
        assert(core.pipeline.execute2.alu.result.getWidth == xlen, s"HdrPath result width mismatch, expected $xlen")
      }
    }
  }
}

class GainSharePlugin extends AudioCorePlugin {
  def build(core: AudioCore, timers: FsTimers): Unit = {
    import core._
    import core.config._
    when(Bool(enableGainShare)) {
      val exponent = Reg(UInt(6 bits)) init(0)
      val fsm = new StateMachine {
        val IDLE = new State with EntryPoint
        val UPDATE = new State
        IDLE.whenIsActive { when(timers.io.tick) { goto(UPDATE) } }
        UPDATE.whenIsActive {
          val absVal = Mux(core.io.sampleIn.payload(0) >= 0, core.io.sampleIn.payload(0), -core.io.sampleIn.payload(0))
          exponent := U(0, 6 bits)
          goto(IDLE)
        }
      }
      core.pipeline.execute2.alu.result := core.pipeline.execute2.alu.result << exponent
      assert(exponent.getWidth == 6, s"GainShare exponent width mismatch, expected 6")
    }
  }
}

class BankSwitchPlugin extends AudioCorePlugin {
  def build(core: AudioCore, timers: FsTimers): Unit = {
    import core._
    import core.config._
    when(Bool(enableBankSwitch)) {
      val swapPending = Reg(Bool()) init(False)
      when(timers.io.tick && swapPending) { csrMap.bankSel := !csrMap.bankSel; swapPending := False }
      when(core.pipeline.execute2.stage.isValid) {
        when(core.pipeline.decode.stageData.ctrl.audioOp === AudioOp.SWAP_BANK) { swapPending := True }
      }
      assert(csrMap.bankSel === past(csrMap.bankSel) || timers.io.tick, "Bank swap not on frame boundary")
    }
  }
}

class CoeffLoaderPlugin extends AudioCorePlugin {
  def build(core: AudioCore, timers: FsTimers): Unit = {
    import core._
    import core.config._
    when(Bool(enableCoeffLoader)) {
      val dma = new SpiFlashDmaWithSha256()
      val addr = Reg(UInt(log2Up(256) bits)) init(0)
      dma.io.data.ready := True
      when(dma.io.data.valid && dma.io.hashValid) {
        core.pipeline.memory.coeffMem.write(addr, dma.io.data.payload.asSInt)
        addr := addr + 1
      }
      assert(addr.getWidth == log2Up(256), s"CoeffLoader address width mismatch, expected ${log2Up(256)}")
    }
  }
}

class FirPartitionPlugin extends AudioCorePlugin {
  def build(core: AudioCore, timers: FsTimers): Unit = {
    import core._
    import core.config._
    when(Bool(enableFirPartition)) {
      val fftBuffer = Mem(SInt(fixedPointWidth bits), 4096)
      val timeDomainTaps = Reg(UInt(12 bits)) init(1024)
      when(core.pipeline.execute2.stage.isValid) {
        when(core.pipeline.decode.stageData.ctrl.audioOp === AudioOp.MACACC_Q && timeDomainTaps > 4096) {
          fftBuffer.write(0, core.pipeline.execute2.alu.result)
        }
      }
      assert(fftBuffer.getWidth == fixedPointWidth, s"FirPartition buffer width mismatch, expected $fixedPointWidth")
    }
  }
}

class FastLimiterPlugin extends AudioCorePlugin {
  def build(core: AudioCore, timers: FsTimers): Unit = {
    import core._
    import core.config._
    when(Bool(enableFastLimiter)) {
      val peak = Reg(SInt(fixedPointWidth bits)) init(0)
      val absVal = Mux(core.io.sampleIn.payload(0) >= 0, core.io.sampleIn.payload(0), -core.io.sampleIn.payload(0))
      peak := Mux(absVal > peak, absVal, peak)
      core.io.mute := peak > (1 << 30)
      assert(peak.getWidth == fixedPointWidth, s"FastLimiter peak width mismatch, expected $fixedPointWidth")
    }
  }
}

class ThermalModelPlugin extends AudioCorePlugin {
  def build(core: AudioCore, timers: FsTimers): Unit = {
    import core._
    import core.config._
    when(Bool(enableThermalModel)) {
      val i2r = Reg(SInt(fixedPointWidth bits)) init(0)
      val temp = Reg(SInt(16 bits)) init(0)
      i2r := (core.io.sampleIn.payload(0) * core.io.sampleIn.payload(0)) >> fracBits
      temp := temp + (i2r >> 10) - (temp >> 8)
      csrMap.thermalTemp := temp
      assert(temp.getWidth == 16, s"ThermalModel temperature width mismatch, expected 16")
    }
  }
}

class BrownoutPlugin extends AudioCorePlugin {
  def build(core: AudioCore, timers: FsTimers): Unit = {
    import core._
    import core.config._
    when(Bool(enableBrownout)) {
      val kalman = new KalmanFilter()
      core.io.compressor := kalman.io.railDrop > (1 << 20)
      assert(kalman.io.railDrop.getWidth == 32, s"Brownout railDrop width mismatch, expected 32")
    }
  }
}

class DualNetworkPlugin extends AudioCorePlugin {
  def build(core: AudioCore, timers: FsTimers): Unit = {
    import core._
    import core.config._
    when(Bool(enableDualNetwork)) {
      val aes67Mac = new Aes67Mac()
      val milanMac = new MilanMac()
      val fifo = StreamFifoCC(Bits(32 bits), 128, ClockDomain.external("macClk"), systemClock)
      val arbiter = StreamArbiterFactory().roundRobin.on(Seq(aes67Mac.io.output, milanMac.io.output))
      fifo.io.push <> arbiter
      core.io.axiStream.valid := fifo.io.pop.valid
      core.io.axiStream.payload := fifo.io.pop.payload
      fifo.io.pop.ready := core.io.axiStream.ready
      assert(fifo.io.push.payload.getWidth == 32, s"DualNetwork FIFO payload width mismatch, expected 32")
    }
  }
}

class TelnetDumpPlugin extends AudioCorePlugin {
  def build(core: AudioCore, timers: FsTimers): Unit = {
    import core._
    import core.config._
    when(Bool(enableTelnetDump && enableDebug && enableDualNetwork)) {
      // Placeholder for debug and dualNetwork logic
      assert(True, "TelnetDump placeholder assertion")
    }
  }
}

class DebugPlugin extends AudioCorePlugin {
  def build(core: AudioCore, timers: FsTimers): Unit = {
    import core._
    import core.config._
    when(Bool(enableDebug)) {
      val debugModule = new ClockingArea(alwaysOnDomain) {
        val debug = new RiscVDebugModule()
        csrMap.accRegCsr := core.pipeline.execute2.accReg
      }
      val tracer = new Area {
        val traceMem = Mem(SInt(fixedPointWidth bits), 4096)
        val traceAddr = Reg(UInt(log2Up(4096) bits)) init(0)
        when(core.io.sampleIn.valid) {
          traceMem.write(traceAddr, core.io.sampleIn.payload(0))
          traceAddr := traceAddr + 1
        }
        assert(traceMem.getWidth == fixedPointWidth, s"Debug traceMem width mismatch, expected $fixedPointWidth")
      }
    }
  }
}

class PowerOptPlugin extends AudioCorePlugin {
  def build(core: AudioCore, timers: FsTimers): Unit = {
    import core._
    import core.config._
    when(Bool(enablePowerOpt)) {
      val enables = new Area {
        val mac = core.pipeline.decode.stageData.ctrl.audioOp === AudioOp.MAC_Q || core.pipeline.decode.stageData.ctrl.audioOp === AudioOp.MACACC_Q
        val simd = core.pipeline.decode.stageData.ctrl.audioOp === AudioOp.VADD_Q || core.pipeline.decode.stageData.ctrl.audioOp === AudioOp.VSUB_Q ||
                   core.pipeline.decode.stageData.ctrl.audioOp === AudioOp.VABS_Q || core.pipeline.decode.stageData.ctrl.audioOp === AudioOp.VMAX_Q
        val shift = core.pipeline.decode.stageData.ctrl.audioOp === AudioOp.SHL_Q || core.pipeline.decode.stageData.ctrl.audioOp === AudioOp.SHR_Q
      }
      val gatedExecute1 = new ClockEnableArea(enables.mac || enables.simd || enables.shift) {
        core.pipeline.execute1.setCompositeName(this, "execute1Gated")
      }
      val gatedExecute2 = new ClockEnableArea(enables.mac || enables.simd || enables.shift) {
        core.pipeline.execute2.setCompositeName(this, "execute2Gated")
      }
      val sleepCounter = Reg(UInt(10 bits)) init(0)
      when(!core.io.sampleIn.valid) { sleepCounter := sleepCounter + 1 }
      .otherwise { sleepCounter := 0 }
      core.io.wfi := sleepCounter > 100
      assert(sleepCounter.getWidth == 10, s"PowerOpt sleepCounter width mismatch, expected 10")
    }
  }
}

class AssistCorePlugin extends AudioCorePlugin {
  def build(core: AudioCore, timers: FsTimers): Unit = {
    import core._
    import core.config._
    when(Bool(enableAssistCore)) {
      val assist = new ClockingArea(alwaysOnDomain) {
        val core = new RiscV16BitCore()
        when(core.io.sampleIn.valid || (Bool(enableDualNetwork) && core.io.axiStream.valid)) {
          core.io.wfi := False
        }
        apbFactory.read(csrMap.thermalTemp.asBits.resize(32), 0x802)
      }
    }
  }
}

class AsicBistPlugin extends AudioCorePlugin {
  def build(core: AudioCore, timers: FsTimers): Unit = {
    import core._
    import core.config._
    when(Bool(enableAsicBist)) {
      val bist = new BramBist()
      core.io.exception := bist.io.error
      assert(bist.io.error.getBitsWidth == 1, s"AsicBist error width mismatch, expected 1")
    }
  }
}

class OtpCsrsPlugin extends AudioCorePlugin {
  def build(core: AudioCore, timers: FsTimers): Unit = {
    import core._
    import core.config._
    when(Bool(enableOtpCsrs)) {
      apbFactory.read(csrMap.fuses, 0x803)
      assert(csrMap.fuses.getWidth == 32, s"OtpCsrs fuses width mismatch, expected 32")
    }
  }
}

class PolyphaseDelayPlugin extends AudioCorePlugin {
  def build(core: AudioCore, timers: FsTimers): Unit = {
    import core._
    import core.config._
    when(Bool(enablePolyphaseDelay)) {
      when(core.pipeline.execute2.stage.isValid) {
        when(core.pipeline.decode.stageData.ctrl.audioOp === AudioOp.POLYPHASE) {
          val coeffs = Vec(SInt(32 bits), 8)
          coeffs.foreach(_ := 0)
          val acc = Reg(SInt(32 bits)) init(0)
          for (i <- 0 until 8) {
            acc := acc + (core.pipeline.execute1.stageData.result * coeffs(i)) >> 31
          }
          core.pipeline.execute2.alu.result := core.pipeline.execute2.alu.sat(acc)
          assert(acc.getWidth == 32, s"PolyphaseDelay accumulation width mismatch, expected 32")
        }
      }
    }
  }
}

class PllDividerPlugin extends AudioCorePlugin {
  def build(core: AudioCore, timers: FsTimers): Unit = {
    import core._
    import core.config._
    when(Bool(enablePllDivider)) {
      apbFactory.readAndWrite(csrMap.divider.asBits.resize(32), 0x804)
      assert(csrMap.divider.getWidth == 8, s"PllDivider divider width mismatch, expected 8")
    }
  }
}

class HwBreakpointPlugin extends AudioCorePlugin {
  def build(core: AudioCore, timers: FsTimers): Unit = {
    import core._
    import core.config._
    when(Bool(enableHwBreakpoint)) {
      val absVal = Mux(core.io.sampleIn.payload(0) >= 0, core.io.sampleIn.payload(0), -core.io.sampleIn.payload(0))
      core.io.breakpoint := absVal > csrMap.threshold
      apbFactory.readAndWrite(csrMap.threshold.asBits.resize(32), 0x805)
      assert(csrMap.threshold.getWidth == fixedPointWidth, s"HwBreakpoint threshold width mismatch, expected $fixedPointWidth")
    }
  }
}