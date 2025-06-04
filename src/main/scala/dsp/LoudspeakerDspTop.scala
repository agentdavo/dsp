package dsp

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba3.apb._
import dsp.plugins._
import dsp.peripherals._
import scala.language.implicitConversions

// Top-level module
class LoudspeakerDspTop(cfg: AudioCoreConfig) extends Component {
  val io = new Bundle {
    val i2sIn = slave Stream(SInt(cfg.fixedPointWidth bits))
    val i2sOut = master Stream(SInt(cfg.fixedPointWidth bits))
    val axiStream = master Stream(Bits(32 bits))
    val mute = out Bool()
    val compressor = out Bool()
    val wfi = out Bool()
    val exception = out Bool()
    val breakpoint = out Bool()
    val apb = slave(Apb3(16, 32))
  }

  val bclkDomain = ClockDomain.external("bclk")
  val core = new AudioCore(cfg)
  val i2sRx = new I2sReceiver(cfg.channels, bclkDomain)
  val i2sTx = new I2sTransmitter(cfg.channels, bclkDomain)
  val dma = new SimpleDma(cfg.fixedPointWidth, cfg.channels, bclkDomain, ClockDomain.current)
  val timers = new FsTimers(48000, 96000)

  i2sRx.io.output <> dma.io.input
  dma.io.output <> i2sTx.io.input
  core.io.sampleIn <> dma.io.toCore
  dma.io.fromCore <> core.io.sampleOut
  io.i2sIn <> i2sRx.io.input
  io.i2sOut <> i2sTx.io.output
  io.axiStream <> dma.io.axiStream
  io.mute <> core.io.mute
  io.compressor <> core.io.compressor
  io.wfi <> core.io.wfi
  io.exception <> core.io.exception
  io.breakpoint <> core.io.breakpoint
  io.apb <> core.io.apb

  // Populate plugins
  val plugins: Vector[AudioCorePlugin] = Vector(
    if (cfg.enablePluginTemplate) Some(new PluginTemplate) else None,
    if (cfg.enableAccRegFile) Some(new AccRegFilePlugin) else None,
    if (cfg.enableMultiRate) Some(new MultiRatePlugin) else None,
    if (cfg.enableHdrPath) Some(new HdrPathPlugin) else None,
    if (cfg.enableGainShare) Some(new GainSharePlugin) else None,
    if (cfg.enableBankSwitch) Some(new BankSwitchPlugin) else None,
    if (cfg.enableCoeffLoader) Some(new CoeffLoaderPlugin) else None,
    if (cfg.enableFirPartition) Some(new FirPartitionPlugin) else None,
    if (cfg.enableFastLimiter) Some(new FastLimiterPlugin) else None,
    if (cfg.enableThermalModel) Some(new ThermalModelPlugin) else None,
    if (cfg.enableBrownout) Some(new BrownoutPlugin) else None,
    if (cfg.enableDualNetwork) Some(new DualNetworkPlugin) else None,
    if (cfg.enableTelnetDump && cfg.enableDebug && cfg.enableDualNetwork) Some(new TelnetDumpPlugin) else None,
    if (cfg.enableDebug) Some(new DebugPlugin) else None,
    if (cfg.enablePowerOpt) Some(new PowerOptPlugin) else None,
    if (cfg.enableAssistCore) Some(new AssistCorePlugin) else None,
    if (cfg.enableAsicBist) Some(new AsicBistPlugin) else None,
    if (cfg.enableOtpCsrs) Some(new OtpCsrsPlugin) else None,
    if (cfg.enablePolyphaseDelay) Some(new PolyphaseDelayPlugin) else None,
    if (cfg.enablePllDivider) Some(new PllDividerPlugin) else None,
    if (cfg.enableHwBreakpoint) Some(new HwBreakpointPlugin) else None
  ).flatten.toVector

  plugins.foreach(_.build(core, timers))
}

// Generate Verilog
object LoudspeakerDspVerilog extends App {
  SpinalVerilog(new LoudspeakerDspTop(AudioCoreConfig()))
}