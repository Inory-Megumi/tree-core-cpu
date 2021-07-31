package treecorel2

import chisel3._

class TreeCoreL2 extends Module with ConstantDefine {
  val io = IO(new Bundle {
    val out1: Bool = Output(Bool())
    val out2: UInt = Output(UInt(RegAddrLen.W))
    val out3: UInt = Output(UInt(BusWidth.W))

  })

  private val pcUnit        = Module(new PCRegister)
  private val instCacheUnit = Module(new InstCache)
  private val if2idUnit     = Module(new IFToID)
  private val regFile       = Module(new RegFile)
  private val instDecoder   = Module(new InstDecoderStage)
  private val id2exUnit     = Module(new IDToEX)
  private val execUnit      = Module(new ExecutionStage)
  private val ex2maUnit     = Module(new EXToMA)
  private val memAccess     = Module(new MemoryAccessStage)
  private val ma2wbUnit     = Module(new MAToWB)

  instCacheUnit.io.instAddrIn := pcUnit.io.instAddrOut
  instCacheUnit.io.instEnaIn  := pcUnit.io.instEnaOut

  // TODO: need to pass extra instAddr to the next stage?
  // if to id
  if2idUnit.io.ifInstAddrIn := pcUnit.io.instAddrOut
  if2idUnit.io.ifInstDataIn := instCacheUnit.io.instDataOut

  // inst decoder
  instDecoder.io.instAddrIn := if2idUnit.io.idInstAddrOut
  instDecoder.io.instDataIn := if2idUnit.io.idInstDataOut
  instDecoder.io.rdDataAIn  := regFile.io.rdDataAOut
  instDecoder.io.rdDataBIn  := regFile.io.rdDataBOut

  regFile.io.rdEnaAIn  := instDecoder.io.rdEnaAOut
  regFile.io.rdAddrAIn := instDecoder.io.rdAddrAOut
  regFile.io.rdEnaBIn  := instDecoder.io.rdEnaBOut
  regFile.io.rdAddrBIn := instDecoder.io.rdAddrBOut

  // id to ex
  id2exUnit.io.idAluOperTypeIn := instDecoder.io.aluOperTypeOut
  id2exUnit.io.idRsValAIn      := instDecoder.io.rsValAOut
  id2exUnit.io.idRsValBIn      := instDecoder.io.rsValBOut
  id2exUnit.io.idWtEnaIn       := instDecoder.io.wtEnaOut
  id2exUnit.io.idWtAddrIn      := instDecoder.io.wtAddrOut
  // ex
  execUnit.io.aluOperTypeIn := id2exUnit.io.exAluOperTypeOut
  execUnit.io.rsValAIn      := id2exUnit.io.exRsValAOut
  execUnit.io.rsValBIn      := id2exUnit.io.exRsValBOut
  // ex to ma
  ex2maUnit.io.exResIn    := execUnit.io.resOut
  ex2maUnit.io.exWtEnaIn  := id2exUnit.io.exWtEnaOut
  ex2maUnit.io.exWtAddrIn := id2exUnit.io.exWtAddrOut
  // ma
  memAccess.io.resIn    := ex2maUnit.io.maResOut
  memAccess.io.wtEnaIn  := ex2maUnit.io.maWtEnaOut
  memAccess.io.wtAddrIn := ex2maUnit.io.maWtAddrOut
  // ma to wb
  ma2wbUnit.io.maResIn    := memAccess.io.resOut
  ma2wbUnit.io.maWtEnaIn  := memAccess.io.wtEnaOut
  ma2wbUnit.io.maWtAddrIn := memAccess.io.wtAddrOut
  // demo
  regFile.io.wtDataIn := ma2wbUnit.io.wbResOut
  regFile.io.wtEnaIn  := ma2wbUnit.io.wbWtEnaOut
  regFile.io.wtAddrIn := ma2wbUnit.io.wbWtAddrOut

  io.out1 := ma2wbUnit.io.wbResOut
  io.out2 := ma2wbUnit.io.wbWtEnaOut
  io.out3 := ma2wbUnit.io.wbWtAddrOut


}

