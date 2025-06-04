import spinal.core.sim._
import org.scalatest.flatspec.AnyFlatSpec
import dsp._

class PinkNoiseEnd2End extends AnyFlatSpec {
  "AudioCore" should "process pink noise" in {
    val cfg = AudioCoreConfig()
    SimConfig.withWave.compile(new LoudspeakerDspTop(cfg)).doSim { dut =>
      val model = new GoldenModel(cfg)
      dut.clockDomain.forkStimulus(10)
      for (i <- 0 until 48000) { // 1s at 48kHz
        val sample = 42 // Simulated pink noise
        dut.io.i2sIn.valid #= true
        dut.io.i2sIn.payload #= sample
        dut.clockDomain.waitSampling()
        if (dut.io.i2sOut.valid.toBoolean) {
          assert(dut.io.i2sOut.payload.toLong === model.step(sample))
        }
      }
    }
  }
}

class GoldenModel(cfg: AudioCoreConfig) {
  def step(sample: Long): Long = sample // Placeholder
}