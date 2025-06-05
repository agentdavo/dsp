package dsp

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable.Queue

class PinkNoiseEnd2End extends AnyFlatSpec {
  "AudioCore" should "process pink noise for 1 second at 48kHz" in {
    val cfg = AudioCoreConfig()
    val clockPeriod = 20 // 20ns = 50MHz
    val sampleRate = 48000
    val totalSamples = sampleRate // 1s = 48,000 samples
    val cyclesPerSample = (1000000000L / sampleRate / clockPeriod).toInt // ~1042 cycles
    val pipelineLatency = 6 // 6-stage pipeline

    SimConfig
      .withVerilator
      .withFstWave
      .withWaveDepth(2) // Corrected from withFstWaveDepth
      .withConfig(SpinalConfig(targetDirectory = "test_waveforms"))
      .allOptimisation
      .workspacePath("simWorkspace")
      .compile(new LoudspeakerDspTop(cfg))
      .doSimUntilVoid("PinkNoiseRegression") { dut =>
        // Initialize signals
        dut.clockDomain.forkStimulus(period = clockPeriod)
        dut.io.i2sIn.valid #= false
        dut.io.i2sIn.payload #= 0
        dut.io.i2sOut.ready #= true
        dut.io.axiStream.ready #= true
        dut.io.apb.PSEL #= 0
        dut.io.apb.PENABLE #= false
        dut.io.apb.PWRITE #= false
        dut.io.apb.PADDR #= 0
        dut.io.apb.PWDATA #= 0

        // Reset
        dut.clockDomain.assertReset()
        sleep(100)
        dut.clockDomain.deassertReset()
        dut.clockDomain.waitSampling(pipelineLatency)

        // Reference model
        val model = new GoldenModel(cfg)
        val expectedOutputs = Queue[Long]()
        var sampleCount = 0

        // Timeout
        SimTimeout(totalSamples * cyclesPerSample * clockPeriod * 3)

        // Stimulus thread
        fork {
          while (sampleCount < totalSamples) {
            val sample = 42 // Simplified pink noise
            dut.io.i2sIn.valid #= true
            dut.io.i2sIn.payload #= sample
            expectedOutputs.enqueue(model.step(sample))
            dut.clockDomain.waitSampling(cyclesPerSample)
            sampleCount += 1
            if (sampleCount % 1000 == 0) println(s"Stimulated $sampleCount samples")
          }
          dut.io.i2sIn.valid #= false
        }

        // Response thread
        fork {
          var checkedSamples = 0
          while (checkedSamples < totalSamples) {
            dut.clockDomain.waitSampling()
            if (dut.io.i2sOut.valid.toBoolean && dut.io.i2sOut.ready.toBoolean && expectedOutputs.nonEmpty) {
              val expected = expectedOutputs.dequeue()
              assert(
                dut.io.i2sOut.payload.toLong === expected,
                s"Output mismatch at sample $checkedSamples: got ${dut.io.i2sOut.payload.toLong}, expected $expected"
              )
              checkedSamples += 1
              if (checkedSamples % 1000 == 0) println(s"Checked $checkedSamples samples")
            }
          }
          simSuccess()
        }

        // Monitor mute/exception
        fork {
          while (sampleCount < totalSamples) {
            dut.clockDomain.waitSampling()
            assert(!dut.io.mute.toBoolean, s"Mute active at cycle ${sampleCount * cyclesPerSample}")
            assert(!dut.io.exception.toBoolean, s"Exception active at cycle ${sampleCount * cyclesPerSample}")
          }
        }
      }
  }
}

class GoldenModel(cfg: AudioCoreConfig) {
  private var acc: Long = 0
  def step(sample: Long): Long = {
    var result = sample
    if (cfg.enableAccRegFile) {
      acc += sample
      val maxVal = (1L << (cfg.fixedPointWidth - 1)) - 1
      val minVal = -(1L << (cfg.fixedPointWidth - 1))
      acc = acc.min(maxVal).max(minVal)
      result = acc
    }
    if (cfg.enableFastLimiter) {
      val absVal = if (result >= 0) result else -result
      if (absVal > (1L << 30)) result = 0
    }
    result
  }
}