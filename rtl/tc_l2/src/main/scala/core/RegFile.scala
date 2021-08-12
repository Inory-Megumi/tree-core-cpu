package treecorel2

import chisel3._
import difftest._

class RegFile(val ifDiffTest: Boolean) extends Module with ConstantDefine {
  val io = IO(new Bundle {
    val rdEnaAIn:  Bool = Input(Bool())
    val rdAddrAIn: UInt = Input(UInt(RegAddrLen.W))

    val rdEnaBIn:  Bool = Input(Bool())
    val rdAddrBIn: UInt = Input(UInt(RegAddrLen.W))

    val wtEnaIn:  Bool = Input(Bool())
    val wtAddrIn: UInt = Input(UInt(RegAddrLen.W))
    val wtDataIn: UInt = Input(UInt(BusWidth.W))

    val rdDataAOut: UInt = Output(UInt(BusWidth.W))
    val rdDataBOut: UInt = Output(UInt(BusWidth.W))
  })

  protected val regFile = Mem(RegNum, UInt(BusWidth.W))
  protected val wtAddr: UInt = io.wtAddrIn

  regFile.write(io.wtAddrIn, Mux(io.wtEnaIn, Mux(io.wtAddrIn === 0.U, 0.U, io.wtDataIn), regFile(io.wtAddrIn)))

  io.rdDataAOut := Mux(io.rdEnaAIn, Mux(io.rdAddrAIn =/= 0.U, regFile(io.rdAddrAIn), 0.U), 0.U)
  io.rdDataBOut := Mux(io.rdEnaBIn, Mux(io.rdAddrBIn =/= 0.U, regFile(io.rdAddrBIn), 0.U), 0.U)

  //@printf(p"[regFile]io.rdEnaAIn = 0x${Hexadecimal(io.rdEnaAIn)}\n")
  //@printf(p"[regFile]io.rdAddrAIn = 0x${Hexadecimal(io.rdAddrAIn)}\n")
  //@printf(p"[regFile]io.rdEnaBIn = 0x${Hexadecimal(io.rdEnaBIn)}\n")
  //@printf(p"[regFile]io.rdAddrBIn = 0x${Hexadecimal(io.rdAddrBIn)}\n")
  //@printf(p"[regFile]io.rdDataAOut = 0x${Hexadecimal(io.rdDataAOut)}\n")
  //@printf(p"[regFile]io.rdDataBOut = 0x${Hexadecimal(io.rdDataBOut)}\n")
  // protected val regFile = Mem(RegNum, UInt(BusWidth.W))
  // TODO: need to solve the when rdAddr* === wtAddrIn(the forward circuit)
  // io.rdDataAOut := Mux(io.rdEnaAIn && (io.rdAddrAIn =/= 0.U), regFile.read(io.rdAddrAIn), 0.U)
  // io.rdDataBOut := Mux(io.rdEnaBIn && (io.rdAddrBIn =/= 0.U), regFile.read(io.rdAddrBIn), 0.U)
  // regFile.write(io.wtAddrIn, Mux(io.wtEnaIn && (io.wtAddrIn =/= 0.U), io.wtDataIn, regFile(io.wtAddrIn)))

  if (ifDiffTest) {
    val diffRegState: DifftestArchIntRegState = Module(new DifftestArchIntRegState)
    diffRegState.io.clock  := this.clock
    diffRegState.io.coreid := 0.U
    // $0 is always zero!!
    diffRegState.io.gpr.zipWithIndex.foreach({
      case (v, i) => v := Mux(i.U === 0.U, 0.U(BusWidth.W), regFile(i.U))
    })
  }
}
