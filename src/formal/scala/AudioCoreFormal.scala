package dsp

import spinal.core.formal._

object AudioCoreFormal {
  def apply(cfg: AudioCoreConfig, core: AudioCore) = {
    val macResult = core.pipeline.execute2.alu.result
    val maxVal = (1L << (cfg.fixedPointWidth - 1)) - 1
    val minVal = -(1L << (cfg.fixedPointWidth - 1))
    assert(macResult <= maxVal && macResult >= minVal, "MAC overflow")
    assert(core.pipeline.memory.cag.idxReg <= core.pipeline.memory.cag.circCtrl.len - 1, "CAG wrap")
    assert(!past(core.pipeline.writeback.stageData.ctrl.isValid) || !core.pipeline.writeback.stageData.ctrl.isValid, "No double retire")
    assert(macResult.abs() <= maxVal, "Zero THD")
    when(Bool(cfg.enableBankSwitch)) {
      assert(core.csrMap.bankSel === past(core.csrMap.bankSel) || core.timers.io.tick, "Bank swap on frame boundary")
    }
  }
}