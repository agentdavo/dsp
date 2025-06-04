import spinal.core.sim._
import dsp._
import scala.language.reflectiveCalls

object PinkNoiseEnd2End extends App {
  val cfg = AudioCoreConfig()
  SimConfig.withWave.compile(new LoudspeakerDspTop(cfg)).doSim { dut =>
    val model = new GoldenModel(cfg)
    dut.clockDomain.forkStimulus(10)
    for (i <- 0 until 48000) {
      val sample = 42
      dut.io.i2sIn.valid #= true
      dut.io.i2sIn.payload #= sample
      dut.clockDomain.waitSampling()
      if (dut.io.i2sOut.valid.toBoolean) {
        assert(dut.io.i2sOut.payload.toLong == model.step(sample))
      }
    }
  }
}

class GoldenModel(cfg: AudioCoreConfig) {
  def step(sample: Long): Long = sample
}
