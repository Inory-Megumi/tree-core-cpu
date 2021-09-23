package treecorel2

import chisel3._
import chisel3.util._
import difftest._
import treecorel2.common.ConstVal._

class CSRReg(val ifDiffTest: Boolean) extends Module with InstConfig {
  val io = IO(new Bundle {
    // from id
    val rdAddrIn:       UInt = Input(UInt(CSRAddrLen.W))
    val instOperTypeIn: UInt = Input(UInt(InstOperTypeLen.W))
    val pcIn:           UInt = Input(UInt(BusWidth.W))
    // from ex's out
    val wtEnaIn:  Bool = Input(Bool())
    val wtDataIn: UInt = Input(UInt(BusWidth.W))
    // from clint
    val intrInfo: INTRIO = Flipped(new INTRIO)
    // from ma
    val pcFromMaIn: UInt = Input(UInt(BusWidth.W))
    // to ex's in
    val rdDataOut: UInt = Output(UInt(BusWidth.W))
    // to difftest
    val ifNeedSkip: Bool = Output(Bool())
    // to id
    val excpJumpInfo: JUMPIO = new JUMPIO
    val intrJumpInfo: JUMPIO = new JUMPIO
  })

  protected val privMode: UInt = RegInit(mPrivMode)
  protected val addrReg:  UInt = RegNext(io.rdAddrIn)
  // machine mode reg
  protected val mstatus: UInt = RegInit("h00001880".U(BusWidth.W))
  protected val mie:     UInt = RegInit(0.U(BusWidth.W))
  protected val mtvec:   UInt = RegInit(0.U(BusWidth.W))
  protected val mepc:    UInt = RegInit(0.U(BusWidth.W))
  protected val mcause:  UInt = RegInit(0.U(BusWidth.W))
  protected val mtval:   UInt = RegInit(0.U(BusWidth.W))
  protected val mip:     UInt = RegInit(0.U(BusWidth.W))
  protected val mcycle:  UInt = RegInit(0.U(BusWidth.W))

  protected val excpJumpType = WireDefault(UInt(JumpTypeLen.W), noJumpType)
  protected val excpJumpAddr = WireDefault(UInt(BusWidth.W), 0.U)
  // difftest run right code in user mode, when throw exception, enter machine mode
  when(io.instOperTypeIn === sysECALLType) {
    mepc         := io.pcIn
    mcause       := 11.U // ecall cause code
    mstatus      := Cat(mstatus(63, 13), privMode(1, 0), mstatus(10, 8), mstatus(3), mstatus(6, 4), 0.U, mstatus(2, 0))
    excpJumpType := csrJumpType
    excpJumpAddr := Cat(mtvec(63, 2), Fill(2, 0.U))
  }

  io.excpJumpInfo.kind := excpJumpType
  io.excpJumpInfo.addr := excpJumpAddr

  // intr
  protected val intrJumpType    = WireDefault(UInt(JumpTypeLen.W), noJumpType)
  protected val intrJumpAddr    = WireDefault(UInt(BusWidth.W), 0.U)
  protected val intrJumpTypeReg = RegNext(intrJumpType)
  when(mstatus(3) === 1.U && mie(7) === 1.U && io.intrInfo.mtip === true.B) {
    mepc         := io.pcFromMaIn
    mcause       := "h8000000000000007".U
    mstatus      := Cat(mstatus(63, 13), privMode(1, 0), mstatus(10, 8), mstatus(3), mstatus(6, 4), 0.U, mstatus(2, 0))
    intrJumpType := csrJumpType
    intrJumpAddr := Cat(mtvec(63, 2), Fill(2, 0.U))
  }

  io.intrJumpInfo.kind := intrJumpType
  io.intrJumpInfo.addr := intrJumpAddr

  // mret
  when(io.instOperTypeIn === sysMRETType) {
    mstatus      := Cat(mstatus(63, 13), uPrivMode(1, 0), mstatus(10, 8), 1.U, mstatus(6, 4), mstatus(7), mstatus(2, 0))
    excpJumpType := csrJumpType
    excpJumpAddr := mepc
    privMode     := mstatus(12, 11) // mstatus.MPP
  }

  //TODO: maybe some bug? the right value after wtena sig trigger
  // the addrReg is also the wt addr
  when(io.wtEnaIn) {
    switch(addrReg) {
      is(mStatusAddr) {
        mstatus := io.wtDataIn
      }
      is(mIeAddr) {
        mie := io.wtDataIn
      }
      is(mTvecAddr) {
        mtvec := io.wtDataIn
      }
      is(mEpcAddr) {
        mepc := io.wtDataIn
      }
      is(mCauseAddr) {
        mcause := io.wtDataIn
      }
      is(mTvalAddr) {
        mtval := io.wtDataIn
      }
      is(mIpAddr) {
        mip := io.wtDataIn
      }
      is(mCycleAddr) {
        mcycle := io.wtDataIn
      }
    }
  }.otherwise {
    mcycle := mcycle + 1.U(BusWidth.W)
  }

  io.rdDataOut := MuxLookup(
    io.rdAddrIn,
    0.U(BusWidth.W),
    Seq(
      mStatusAddr -> mstatus,
      mIeAddr     -> mie,
      mTvecAddr   -> mtvec,
      mEpcAddr    -> mepc,
      mCauseAddr  -> mcause,
      mTvalAddr   -> mtval,
      mIpAddr     -> mip,
      mCycleAddr  -> mcycle
    )
  )

  when(io.rdAddrIn === mCycleAddr) {
    io.ifNeedSkip := true.B
  }.otherwise {
    io.ifNeedSkip := false.B
  }

  if (ifDiffTest) {
    val diffArchState = Module(new DifftestArchEvent())
    diffArchState.io.clock       := this.clock
    diffArchState.io.coreid      := 0.U
    diffArchState.io.intrNO      := Mux(intrJumpTypeReg === csrJumpType, 7.U, 0.U)
    diffArchState.io.cause       := 0.U
    diffArchState.io.exceptionPC := Mux(intrJumpTypeReg === csrJumpType, mepc, 0.U)
    // diffArchState.io.exceptionInst := 0.U // FIXME: current version don't have this api, maybe need to update the difftest

    val diffCsrState = Module(new DifftestCSRState())
    diffCsrState.io.clock          := this.clock
    diffCsrState.io.coreid         := 0.U
    diffCsrState.io.mstatus        := mstatus
    diffCsrState.io.mcause         := mcause
    diffCsrState.io.mepc           := mepc
    diffCsrState.io.sstatus        := 0.U
    diffCsrState.io.scause         := 0.U
    diffCsrState.io.sepc           := 0.U
    diffCsrState.io.satp           := 0.U
    diffCsrState.io.mip            := 0.U
    diffCsrState.io.mie            := mie
    diffCsrState.io.mscratch       := 0.U
    diffCsrState.io.sscratch       := 0.U
    diffCsrState.io.mideleg        := 0.U
    diffCsrState.io.medeleg        := 0.U
    diffCsrState.io.mtval          := 0.U
    diffCsrState.io.stval          := 0.U
    diffCsrState.io.mtvec          := mtvec //exec
    diffCsrState.io.stvec          := 0.U
    diffCsrState.io.priviledgeMode := privMode
  }
}
