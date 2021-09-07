package treecorel2

import chisel3._
import difftest._

class TreeCoreL2(val ifDiffTest: Boolean = false) extends Module with AXI4Config with InstConfig {
  val io = IO(new Bundle {
    val inst: AXI4USERIO = Flipped(new AXI4USERIO)
    val mem:  AXI4USERIO = Flipped(new AXI4USERIO)
  })
  // tmp
  io.inst.req   := 0.U // 0: read 1: write
  io.inst.wdata := DontCare

  protected val pcUnit      = Module(new PCReg)
  protected val if2idUnit   = Module(new IFToID)
  protected val regFile     = Module(new RegFile(ifDiffTest))
  protected val instDecoder = Module(new InstDecoderStage)
  protected val id2exUnit   = Module(new IDToEX)
  protected val execUnit    = Module(new ExecutionStage)
  protected val ex2maUnit   = Module(new EXToMA)
  protected val memAccess   = Module(new MemoryAccessStage)
  protected val ma2wbUnit   = Module(new MAToWB)
  protected val forwardUnit = Module(new ForWard)
  protected val controlUnit = Module(new Control)
  protected val csrUnit     = Module(new CSRReg)

  io.inst.valid := pcUnit.io.instValidOut
  io.inst.size  := pcUnit.io.instSizeOut
  io.inst.addr  := pcUnit.io.instAddrOut
  io.mem.valid  := memAccess.io.memValidOut
  io.mem.req    := memAccess.io.memReqOut
  io.mem.wdata  := memAccess.io.memDataOut
  io.mem.addr   := memAccess.io.memAddrOut
  io.mem.size   := memAccess.io.memSizeOut

  // ex to pc
  pcUnit.io.ifJumpIn      := controlUnit.io.ifJumpOut
  pcUnit.io.newInstAddrIn := controlUnit.io.newInstAddrOut
  pcUnit.io.stallIfIn     := controlUnit.io.stallIfOut
  // axi to pc
  pcUnit.io.instReadyIn  := io.inst.ready
  pcUnit.io.instRdDataIn := io.inst.rdata
  pcUnit.io.instRespIn   := io.inst.resp
  // TODO: need to pass extra instAddr to the next stage?
  // if to id
  if2idUnit.io.ifInstAddrIn := pcUnit.io.instAddrOut
  if2idUnit.io.ifInstDataIn := pcUnit.io.instDataOut
  if2idUnit.io.ifFlushIn    := controlUnit.io.flushIfOut

  // id
  instDecoder.io.instAddrIn := if2idUnit.io.idInstAddrOut
  instDecoder.io.instDataIn := if2idUnit.io.idInstDataOut
  instDecoder.io.rdDataAIn  := regFile.io.rdDataAOut
  instDecoder.io.rdDataBIn  := regFile.io.rdDataBOut

  // for load correlation
  instDecoder.io.exuOperTypeIn := id2exUnit.io.exAluOperTypeOut
  instDecoder.io.exuWtAddrIn   := id2exUnit.io.exWtAddrOut

  regFile.io.rdEnaAIn  := instDecoder.io.rdEnaAOut
  regFile.io.rdAddrAIn := instDecoder.io.rdAddrAOut
  regFile.io.rdEnaBIn  := instDecoder.io.rdEnaBOut
  regFile.io.rdAddrBIn := instDecoder.io.rdAddrBOut

  // id to ex
  id2exUnit.io.idAluOperTypeIn := instDecoder.io.exuOperTypeOut
  id2exUnit.io.idRsValAIn      := instDecoder.io.rsValAOut
  id2exUnit.io.idRsValBIn      := instDecoder.io.rsValBOut
  id2exUnit.io.idWtEnaIn       := instDecoder.io.wtEnaOut
  id2exUnit.io.idWtAddrIn      := instDecoder.io.wtAddrOut
  id2exUnit.io.lsuFunc3In      := instDecoder.io.lsuFunc3Out
  id2exUnit.io.lsuWtEnaIn      := instDecoder.io.lsuWtEnaOut
  id2exUnit.io.ifFlushIn       := controlUnit.io.flushIdOut
  // ex
  execUnit.io.offsetIn := instDecoder.io.exuOffsetOut // important!!!

  execUnit.io.exuOperTypeIn := id2exUnit.io.exAluOperTypeOut
  execUnit.io.rsValAIn      := id2exUnit.io.exRsValAOut
  execUnit.io.rsValBIn      := id2exUnit.io.exRsValBOut
  execUnit.io.csrRdDataIn   := RegNext(csrUnit.io.rdDataOut) // TODO: need to refactor
  // ex to ma
  ex2maUnit.io.exDataIn   := execUnit.io.wtDataOut
  ex2maUnit.io.exWtEnaIn  := id2exUnit.io.exWtEnaOut
  ex2maUnit.io.exWtAddrIn := id2exUnit.io.exWtAddrOut

  ex2maUnit.io.lsuFunc3In    := id2exUnit.io.lsuFunc3Out
  ex2maUnit.io.lsuWtEnaIn    := id2exUnit.io.lsuWtEnaOut
  ex2maUnit.io.lsuOperTypeIn := execUnit.io.exuOperTypeIn
  ex2maUnit.io.lsuValAIn     := execUnit.io.rsValAIn
  ex2maUnit.io.lsuValBIn     := execUnit.io.rsValBIn
  ex2maUnit.io.lsuOffsetIn   := RegNext(execUnit.io.offsetIn) // important!!

  // ma
  memAccess.io.memFunc3In    := ex2maUnit.io.lsuFunc3Out
  memAccess.io.memOperTypeIn := ex2maUnit.io.lsuOperTypeOut
  memAccess.io.memValAIn     := ex2maUnit.io.lsuValAOut
  memAccess.io.memValBIn     := ex2maUnit.io.lsuValBOut
  memAccess.io.memOffsetIn   := ex2maUnit.io.lsuOffsetOut

  memAccess.io.wtDataIn := ex2maUnit.io.maDataOut
  memAccess.io.wtEnaIn  := ex2maUnit.io.maWtEnaOut
  memAccess.io.wtAddrIn := ex2maUnit.io.maWtAddrOut

  memAccess.io.memReadyIn  := io.mem.ready
  memAccess.io.memRdDataIn := io.mem.rdata
  memAccess.io.memRespIn   := io.mem.resp
  ex2maUnit.io.lsuWtEnaOut := DontCare

  // ma to wb
  ma2wbUnit.io.maDataIn   := memAccess.io.wtDataOut
  ma2wbUnit.io.maWtEnaIn  := memAccess.io.wtEnaOut
  ma2wbUnit.io.maWtAddrIn := memAccess.io.wtAddrOut
  // wb
  regFile.io.wtDataIn := ma2wbUnit.io.wbDataOut
  regFile.io.wtEnaIn  := ma2wbUnit.io.wbWtEnaOut
  regFile.io.wtAddrIn := ma2wbUnit.io.wbWtAddrOut

  // forward control unit
  forwardUnit.io.exDataIn   := ex2maUnit.io.exDataIn
  forwardUnit.io.exWtEnaIn  := ex2maUnit.io.exWtEnaIn
  forwardUnit.io.exWtAddrIn := ex2maUnit.io.exWtAddrIn

  // maDataIn only come from regfile and imm
  // maDataOut have right data include load/store inst and alu calc
  forwardUnit.io.maDataIn   := ma2wbUnit.io.maDataIn
  forwardUnit.io.maWtEnaIn  := ma2wbUnit.io.maWtEnaIn
  forwardUnit.io.maWtAddrIn := ma2wbUnit.io.maWtAddrIn

  forwardUnit.io.idRdEnaAIn  := instDecoder.io.rdEnaAOut
  forwardUnit.io.idRdAddrAIn := instDecoder.io.rdAddrAOut
  forwardUnit.io.idRdEnaBIn  := instDecoder.io.rdEnaBOut
  forwardUnit.io.idRdAddrBIn := instDecoder.io.rdAddrBOut

  instDecoder.io.fwRsEnaAIn := forwardUnit.io.fwRsEnaAOut
  instDecoder.io.fwRsValAIn := forwardUnit.io.fwRsValAOut
  instDecoder.io.fwRsEnaBIn := forwardUnit.io.fwRsEnaBOut
  instDecoder.io.fwRsValBIn := forwardUnit.io.fwRsValBOut

  // branch and load/store control
  controlUnit.io.jumpTypeIn       := instDecoder.io.jumpTypeOut
  controlUnit.io.newInstAddrIn    := instDecoder.io.newInstAddrOut
  controlUnit.io.stallReqFromIDIn := instDecoder.io.stallReqFromIDOut
  controlUnit.io.stallReqFromMaIn := memAccess.io.stallReqOut

  // csr
  csrUnit.io.rdAddrIn := instDecoder.io.csrAddrOut
  csrUnit.io.wtEnaIn  := execUnit.io.csrwtEnaOut
  csrUnit.io.wtDataIn := execUnit.io.csrWtDataOut

  if (ifDiffTest) {
    // commit
    val diffCommitState: DifftestInstrCommit = Module(new DifftestInstrCommit())
    val instValidWire = pcUnit.io.instEnaOut && !this.reset.asBool() && (pcUnit.io.instDataOut =/= 0.U)

    diffCommitState.io.clock  := this.clock
    diffCommitState.io.coreid := 0.U
    diffCommitState.io.index  := 0.U
    // skip the flush inst(nop) maybe the skip cust oper only
    diffCommitState.io.skip := Mux(
      diffCommitState.io.instr === 0x0000007b.U ||
        RegNext(RegNext(RegNext(RegNext(csrUnit.io.ifNeedSkip)))),
      true.B,
      false.B
    )
    diffCommitState.io.isRVC    := false.B
    diffCommitState.io.scFailed := false.B

    diffCommitState.io.valid := RegNext(RegNext(RegNext(RegNext(RegNext(instValidWire))))) &
      (!RegNext(RegNext(RegNext(RegNext(if2idUnit.io.diffIfSkipInstOut))))) &
      (!RegNext(RegNext(RegNext(id2exUnit.io.diffIdSkipInstOut))))

    diffCommitState.io.pc := RegNext(RegNext(RegNext(RegNext(RegNext(pcUnit.io.instAddrOut)))))
    // diffCommitState.io.pc    := RegNext(RegNext(RegNext(RegNext(if2idUnit.io.idInstAddrOut))))

    diffCommitState.io.instr := RegNext(RegNext(RegNext(RegNext(RegNext(pcUnit.io.instDataOut))))) // important!!!
    // diffCommitState.io.instr := RegNext(RegNext(RegNext(RegNext(if2idUnit.io.idInstDataOut)))) // important!!!
    diffCommitState.io.wen   := RegNext(ma2wbUnit.io.wbWtEnaOut)
    diffCommitState.io.wdata := RegNext(ma2wbUnit.io.wbDataOut)
    diffCommitState.io.wdest := RegNext(ma2wbUnit.io.wbWtAddrOut)

    val debugCnt: UInt = RegInit(0.U(5.W))
    when(pcUnit.io.instAddrOut === "h80000014".U) {
      printf(p"[pc]io.inst.addr[pre] = 0x${Hexadecimal(pcUnit.io.instAddrOut)}\n")
    }

    when(pcUnit.io.instDataOut =/= NopInst.U) {
      debugCnt := 5.U
    }

    when(debugCnt =/= 0.U) {
      debugCnt := debugCnt - 1.U
      printf("debugCnt: %d\n", debugCnt)
      printf(p"[pc]io.instDataOut = 0x${Hexadecimal(pcUnit.io.instDataOut)}\n")
      printf(p"[pc]io.inst.addr = 0x${Hexadecimal(pcUnit.io.instAddrOut)}\n")
      printf(p"[pc]io.instEnaOut = 0x${Hexadecimal(pcUnit.io.instEnaOut)}\n")
      // printf(p"[pc]dirty = 0x${Hexadecimal(pcUnit.dirty)}\n")

      printf(p"[if2id]io.ifFlushIn = 0x${Hexadecimal(if2idUnit.io.ifFlushIn)}\n")
      printf(p"[if2id]io.diffIfSkipInstOut = 0x${Hexadecimal(if2idUnit.io.diffIfSkipInstOut)}\n")
      printf(p"[if2id]io.idInstAddrOut = 0x${Hexadecimal(if2idUnit.io.idInstAddrOut)}\n")
      printf(p"[if2id]io.idInstDataOut = 0x${Hexadecimal(if2idUnit.io.idInstDataOut)}\n")

      printf(p"[id]io.instDataIn = 0x${Hexadecimal(instDecoder.io.instDataIn)}\n")
      printf(p"[id]io.rdEnaAOut = 0x${Hexadecimal(instDecoder.io.rdEnaAOut)}\n")
      printf(p"[id]io.rdAddrAOut = 0x${Hexadecimal(instDecoder.io.rdAddrAOut)}\n")
      printf(p"[id]io.rdEnaBOut = 0x${Hexadecimal(instDecoder.io.rdEnaBOut)}\n")
      printf(p"[id]io.rdAddrBOut = 0x${Hexadecimal(instDecoder.io.rdAddrBOut)}\n")
      // printf(p"[id]io.exuOperTypeOut = 0x${Hexadecimal(instDecoder.io.exuOperTypeOut)}\n")
      // printf(p"[id]io.lsuWtEnaOut = 0x${Hexadecimal(instDecoder.io.lsuWtEnaOut)}\n")
      printf(p"[id]io.rsValAOut = 0x${Hexadecimal(instDecoder.io.rsValAOut)}\n")
      printf(p"[id]io.rsValBOut = 0x${Hexadecimal(instDecoder.io.rsValBOut)}\n")

      printf(p"[id]io.wtEnaOut = 0x${Hexadecimal(instDecoder.io.wtEnaOut)}\n")
      printf(p"[id]io.wtAddrOut = 0x${Hexadecimal(instDecoder.io.wtAddrOut)}\n")

      printf(p"[ex]io.wtDataOut = 0x${Hexadecimal(execUnit.io.wtDataOut)}\n")

      when(memAccess.io.memReadyIn) {
        printf("########################################\n")
        printf(p"[ma]io.wtDataOut = 0x${Hexadecimal(memAccess.io.memReadyIn)}\n")
        printf("########################################\n")
      }

      printf(p"[ma]memAccess.io.stallReqOut = 0x${Hexadecimal(memAccess.io.stallReqOut)}\n")
      printf(p"[ma]io.wtDataOut = 0x${Hexadecimal(memAccess.io.wtDataOut)}\n")
      printf(p"[ma]io.wtEnaOut = 0x${Hexadecimal(memAccess.io.wtEnaOut)}\n")
      printf(p"[ma]io.wtAddrOut = 0x${Hexadecimal(memAccess.io.wtAddrOut)}\n")

      printf(p"[main]diffCommitState.io.pc = 0x${Hexadecimal(diffCommitState.io.pc)}\n")
      printf(p"[main]diffCommitState.io.instr = 0x${Hexadecimal(diffCommitState.io.instr)}\n")
      printf(p"[main]diffCommitState.io.skip = 0x${Hexadecimal(diffCommitState.io.skip)}\n")
      printf(p"[main]diffCommitState.io.valid = 0x${Hexadecimal(diffCommitState.io.valid)}\n")
      printf(p"[main]t1 = 0x${Hexadecimal(regFile.io.debugOut)}\n")
      printf("\n")
    }

    // output custom putch oper for 0x7B
    when(diffCommitState.io.instr === 0x0000007b.U) {
      printf("%c", regFile.io.charDataOut)
    }

    // when(diffCommitState.io.skip) {
    //   printf("t0: %d\n", regFile.io.debugOut)
    // }

    // printf(p"[main]diffCommitState.io.pc(pre) = 0x${Hexadecimal(RegNext(RegNext(RegNext(RegNext(pcUnit.io.instAddrOut)))))}\n")
    // printf(p"[main]diffCommitState.io.instr(pre) = 0x${Hexadecimal(RegNext(RegNext(RegNext(if2idUnit.io.idInstDataOut))))}\n")
    // printf("\n")
    // CSR State
    val diffCsrState = Module(new DifftestCSRState())
    diffCsrState.io.clock          := this.clock
    diffCsrState.io.coreid         := 0.U
    diffCsrState.io.mstatus        := 0.U
    diffCsrState.io.mcause         := 0.U
    diffCsrState.io.mepc           := 0.U
    diffCsrState.io.sstatus        := 0.U
    diffCsrState.io.scause         := 0.U
    diffCsrState.io.sepc           := 0.U
    diffCsrState.io.satp           := 0.U
    diffCsrState.io.mip            := 0.U
    diffCsrState.io.mie            := 0.U
    diffCsrState.io.mscratch       := 0.U
    diffCsrState.io.sscratch       := 0.U
    diffCsrState.io.mideleg        := 0.U
    diffCsrState.io.medeleg        := 0.U
    diffCsrState.io.mtval          := 0.U
    diffCsrState.io.stval          := 0.U
    diffCsrState.io.mtvec          := 0.U
    diffCsrState.io.stvec          := 0.U
    diffCsrState.io.priviledgeMode := 0.U

    // trap event
    val diffTrapState = Module(new DifftestTrapEvent)
    val instCnt       = RegInit(0.U(BusWidth.W))
    val cycleCnt      = RegInit(0.U(BusWidth.W))
    val trapReg       = RegNext(RegNext(RegNext(RegNext(RegNext(pcUnit.io.instDataOut === TrapInst.U, false.B)))))

    instCnt  := instCnt + instValidWire
    cycleCnt := Mux(trapReg, 0.U, cycleCnt + 1.U)

    diffTrapState.io.clock    := this.clock
    diffTrapState.io.coreid   := 0.U
    diffTrapState.io.valid    := trapReg
    diffTrapState.io.code     := 0.U // GoodTrap
    diffTrapState.io.pc       := RegNext(RegNext(RegNext(RegNext(RegNext(pcUnit.io.instAddrOut)))))
    diffTrapState.io.cycleCnt := cycleCnt
    diffTrapState.io.instrCnt := instCnt
  } // ifDiffTest
}
