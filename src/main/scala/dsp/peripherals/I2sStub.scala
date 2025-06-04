package dsp.peripherals

import spinal.core._
import spinal.lib._

// Placeholder peripherals
class I2sReceiver(channels: Int, bclkDomain: ClockDomain) extends Component {
  val io = new Bundle {
    val input = slave Stream(SInt(32 bits))
    val output = master Stream(Vec(SInt(32 bits), channels))
  }
  val area = new ClockingArea(bclkDomain) {
    val frame = Reg(Vec(SInt(32 bits), channels)) init(0)
    val counter = Reg(UInt(log2Up(channels) bits)) init(0)
    when(io.input.valid) {
      frame(counter) := io.input.payload
      counter := counter + 1
      when(counter === channels - 1) {
        io.output.payload := frame
        io.output.valid := True
        counter := 0
      }
    }.otherwise {
      io.output.valid := False
    }
    io.input.ready := True
    assert(frame(0).getWidth === 32, s"I2sReceiver frame width mismatch, expected 32")
  }
}

class I2sTransmitter(channels: Int, bclkDomain: ClockDomain) extends Component {
  val io = new Bundle {
    val input = slave Stream(Vec(SInt(32 bits), channels))
    val output = master Stream(SInt(32 bits))
  }
  val area = new ClockingArea(bclkDomain) {
    val counter = Reg(UInt(log2Up(channels) bits)) init(0)
    io.output.payload := io.input.payload(counter)
    io.output.valid := io.input.valid
    when(io.input.valid) {
      counter := counter + 1
      when(counter === channels - 1) { counter := 0 }
    }
    io.input.ready := True
    assert(io.output.payload.getWidth === 32, s"I2sTransmitter output width mismatch, expected 32")
  }
}

class SimpleDma(width: Int, channels: Int, bclkDomain: ClockDomain, systemClock: ClockDomain) extends Component {
  val io = new Bundle {
    val input = slave Stream(Vec(SInt(width bits), channels))
    val output = master Stream(Vec(SInt(width bits), channels))
    val toCore = master Stream(Vec(SInt(width bits), channels))
    val fromCore = slave Stream(Vec(SInt(width bits), channels))
    val axiStream = master Stream(Bits(32 bits))
  }
  val fifoIn = StreamFifoCC(Vec(SInt(width bits), channels), 4, bclkDomain, systemClock)
  val fifoOut = StreamFifoCC(Vec(SInt(width bits), channels), 4, systemClock, bclkDomain)
  fifoIn.io.push <> io.input
  io.toCore <> fifoIn.io.pop
  fifoOut.io.push <> io.fromCore
  io.output <> fifoOut.io.pop
  io.axiStream.payload := io.toCore.payload(0).asBits
  io.axiStream.valid := io.toCore.valid
  assert(io.toCore.payload(0).getWidth === width, s"SimpleDma toCore payload width mismatch, expected $width")
}

class HalfBandFir(taps: Int) extends Component {
  val io = new Bundle {
    val input = in SInt(32 bits)
    val output = out SInt(32 bits)
  }
  val coeffs = Vec(SInt(32 bits), taps)
  coeffs.foreach(_ := 0)
  val delayLine = Reg(Vec(SInt(32 bits), taps)) init(0)
  val acc = Reg(SInt(32 bits)) init(0)
  delayLine(0) := io.input
  for (i <- 1 until taps) { delayLine(i) := delayLine(i - 1) }
  acc := 0
  for (i <- 0 until taps) { acc := acc + (delayLine(i) * coeffs(i)) >> 31 }
  io.output := acc
  assert(io.output.getWidth === 32, s"HalfBandFir output width mismatch, expected 32")
}

class SpiFlashDmaWithSha256 extends Component {
  val io = new Bundle {
    val data = master Stream(Bits(32 bits))
    val hashValid = out Bool()
  }
  val dataReg = Reg(Bits(32 bits)) init(0)
  val hash = Reg(Bits(256 bits)) init(0)
  io.data.payload := dataReg
  io.data.valid := True
  io.hashValid := hash =/= 0
  io.data.ready := True
  assert(io.data.payload.getWidth === 32, s"SpiFlashDmaWithSha256 data width mismatch, expected 32")
}

class Aes67Mac extends Component {
  val io = new Bundle {
    val output = master Stream(Bits(32 bits))
  }
  io.output.payload := 0
  io.output.valid := False
  assert(io.output.payload.getWidth === 32, s"Aes67Mac output width mismatch, expected 32")
}

class MilanMac extends Component {
  val io = new Bundle {
    val output = master Stream(Bits(32 bits))
  }
  io.output.payload := 0
  io.output.valid := False
  assert(io.output.payload.getWidth === 32, s"MilanMac output width mismatch, expected 32")
}

class KalmanFilter extends Component {
  val io = new Bundle {
    val railDrop = out SInt(32 bits)
  }
  val state = Reg(SInt(32 bits)) init(0)
  io.railDrop := state
  assert(io.railDrop.getWidth === 32, s"KalmanFilter railDrop width mismatch, expected 32")
}

class RiscVDebugModule extends Component {
  val io = new Bundle {
    val apb = slave(Apb3(16, 32))
  }
  io.apb.PRDATA := 0
  io.apb.PREADY := True
  assert(io.apb.PRDATA.getWidth === 32, s"RiscVDebugModule PRDATA width mismatch, expected 32")
}

class RiscV16BitCore extends Component {
  val io = new Bundle {
    val apb = slave(Apb3(16, 32))
    val sampleIn = slave Stream(SInt(32 bits))
    val axiStream = master Stream(Bits(32 bits))
    val wfi = out Bool()
  }
  io.apb.PRDATA := 0
  io.apb.PREADY := True
  io.sampleIn.ready := True
  io.axiStream.payload := 0
  io.axiStream.valid := False
  io.wfi := False
  assert(io.apb.PRDATA.getWidth === 32, s"RiscV16BitCore PRDATA width mismatch, expected 32")
}

class BramBist extends Component {
  val io = new Bundle {
    val error = out Bool()
  }
  io.error := False
  assert(io.error.getWidth === 1, s"BramBist error width mismatch, expected 1")
}