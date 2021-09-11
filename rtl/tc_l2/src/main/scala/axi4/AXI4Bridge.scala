package treecorel2

import chisel3._
import chisel3.util._

class AXI4Bridge extends Module with AXI4Config with InstConfig {
  val io = IO(new Bundle {
    val rw:  AXI4USERIO = new AXI4USERIO
    val axi: AXI4IO     = new AXI4IO
  })

  protected val wtTrans: Bool = WireDefault(io.rw.req === AxiReqWt.U)
  protected val rdTrans: Bool = WireDefault(io.rw.req === AxiReqRd.U)

  // valid triggered when multi master start an r/w oper and send valid signal in meantime
  // this time, master start a r/w transition
  protected val wtValid: Bool = WireDefault(wtTrans & io.rw.valid)
  protected val rdValid: Bool = WireDefault(rdTrans & io.rw.valid)

  // handshake sign come from axi
  protected val awHdShk: Bool = WireDefault(io.axi.aw.valid & io.axi.aw.rwReady)
  protected val wtHdShk: Bool = WireDefault(io.axi.wt.valid & io.axi.wt.rwReady)
  protected val bHdShk:  Bool = WireDefault(io.axi.b.valid & io.axi.b.rwReady)
  protected val arHdShk: Bool = WireDefault(io.axi.ar.valid & io.axi.ar.rwReady)
  protected val rdHdShk: Bool = WireDefault(io.axi.rd.valid & io.axi.rd.rwReady)

  // after handshake, the transition end sign
  protected val wtDone:    Bool = WireDefault(wtHdShk & io.axi.wt.last)
  protected val rdDone:    Bool = WireDefault(rdHdShk & io.axi.rd.last) // according to id to identify the rd master
  protected val transDone: Bool = WireDefault(Mux(wtTrans, bHdShk, rdDone))

  val eumWtIDLE :: eumWtADDR :: eumWtWRITE :: eumWtRESP :: Nil = Enum(4)
  val wtOperState: UInt = RegInit(eumWtIDLE)

  when(wtValid) {
    switch(wtOperState) {
      is(eumWtIDLE) {
        wtOperState := eumWtADDR
      }
      is(eumWtADDR) {
        when(awHdShk) { wtOperState := eumWtWRITE }
      }
      is(eumWtWRITE) {
        when(wtDone) { wtOperState := eumWtRESP }
      }
      is(eumWtRESP) {
        when(bHdShk) { wtOperState := eumWtIDLE }
      }
    }
  }

  // read oper
  val eumRdIDLE :: eumRdADDR :: eumRdREAD :: Nil = Enum(3)
  val rdOperState: UInt = RegInit(eumRdIDLE)

  when(rdValid) {
    switch(rdOperState) {
      is(eumRdIDLE) {
        rdOperState := eumRdADDR
      }
      is(eumRdADDR) {
        when(arHdShk) { rdOperState := eumRdREAD }
      }
      is(eumRdREAD) {
        when(rdDone) { rdOperState := eumRdIDLE }
      }
    }
  }

  protected val aligned: Bool = WireDefault(io.rw.addr(ALIGNED_WIDTH - 1, 0) === 0.U)

  protected val addrOp1: UInt = Wire(UInt(4.W))
  addrOp1 := Cat(Fill(4 - ALIGNED_WIDTH, 0.U), io.rw.addr(ALIGNED_WIDTH - 1, 0))
  protected val addrOp2: UInt = Wire(UInt(4.W))
  addrOp2 := Mux(
    io.rw.size === "b00".U,
    "b0000".U,
    Mux(io.rw.size === "b01".U, "b0001".U, Mux(io.rw.size === "b10".U, "b0011".U, "b0111".U))
  )

  protected val addrEnd: UInt = Wire(UInt(4.W))
  addrEnd := addrOp1 + addrOp2
  protected val overStep: Bool = WireDefault(addrEnd(3, ALIGNED_WIDTH) =/= 0.U)

  protected val axiLen: UInt = Wire(UInt(8.W))
  axiLen := Mux(aligned, (TRANS_LEN - 1).U, Cat(Fill(7, 0.U), overStep))

  protected val rwLen:       UInt = RegInit(0.U(8.W))
  protected val rwLenRst:    Bool = (wtTrans & eumWtIDLE) | (rdTrans & eumRdIDLE)
  protected val rwLenIncEna: Bool = (rwLen =/= axiLen) & (wtHdShk | rdHdSh)

  when(rwLenRst) {
    rwLen := 0.U
  }.elsewhen(rwLenIncEna) {
    rwLen := rwLen + 1.U
  }

  protected val axiSize: UInt = Wire(UInt(3.W))
  axiSize := AXI_SIZE.U

  protected val axiAddr: UInt = Wire(UInt(AxiAddrWidth.W))
  axiAddr := Cat(io.rw.addr(AxiAddrWidth - 1, ALIGNED_WIDTH), Fill(ALIGNED_WIDTH, 0.U))

  //aligned_offset_l=0, aligned_offset_h=64
  protected val alignedOffsetLow: UInt = Wire(UInt(OFFSET_WIDTH.W))
  alignedOffsetLow := Cat(Fill(OFFSET_WIDTH - ALIGNED_WIDTH, 0.U), io.rw.addr(ALIGNED_WIDTH - 1, 0)) << 3

  protected val alignedOffsetHig: UInt = Wire(UInt(OFFSET_WIDTH.W))
  alignedOffsetHig := AxiDataWidth.U - alignedOffsetLow

  protected val mask: UInt = Wire(UInt(MASK_WIDTH.W))
  mask := Mux(
    io.rw.size === "b00".U,
    "hff".U << alignedOffsetLow,
    Mux(io.rw.size === "b01".U, "hffff".U << alignedOffsetLow, Mux(io.rw.size === "b10".U, "hffffffff".U, "hffffffffffffffff".U))
  )

  protected val maskLow: UInt = Wire(UInt(AxiDataWidth.W))
  maskLow := mask(AxiDataWidth - 1, 0)

  protected val maskHig: UInt = Wire(UInt(AxiDataWidth.W))
  maskHig := mask(MASK_WIDTH - 1, AxiDataWidth)

  protected val axiId: UInt = Wire(UInt(AxiIdLen.W))
  // axiId := Fill(AxiIdLen, 0.U)
  axiId := io.rw.id

  protected val axiUser: UInt = Wire(UInt(AxiUserLen.W))
  axiUser := Fill(AxiUserLen, 0.U)

  protected val rwReadyEna: Bool = Wire(Bool())
  protected val rwReady:    Bool = RegEnable(transDone, false.B, rwReadyEna)
  rwReadyEna  := transDone | rwReady
  io.rw.ready := rwReady

  protected val rwResp: UInt = RegEnable(Mux(wtTrans, io.axi.b.resp, io.axi.rd.resp), 0.U, transDone)
  io.rw.resp := rwResp

  // ------------------Write Transaction------------------
  io.axi.aw.valid := WireDefault(wtOperState === eumWtADDR)
  io.axi.aw.addr  := axiAddr
  io.axi.aw.prot  := AXI_PROT_UNPRIVILEGED_ACCESS | AXI_PROT_SECURE_ACCESS | AXI_PROT_DATA_ACCESS
  io.axi.aw.id    := axiId
  io.axi.aw.user  := axiUser
  io.axi.aw.len   := axiLen
  io.axi.aw.size  := axiSize
  io.axi.aw.burst := AXI_BURST_TYPE_INCR
  io.axi.aw.lock  := 0.U
  io.axi.aw.cache := AXI_ARCACHE_NORMAL_NON_CACHEABLE_NON_BUFFERABLE
  io.axi.aw.qos   := 0.U
  io.axi.wt.valid := WireDefault(wtOperState === eumWtWRITE)
  io.axi.wt.strb  := "b11111111".U
  io.axi.wt.last  := WireDefault(rwLen === axiLen)
  io.axi.wt.user  := axiUser
  io.axi.wt.id    := DontCare
  // prepare write data
  protected val rwWtData: UInt = RegInit(0.U(AxiDataWidth.W))
  for (i <- 0 until TRANS_LEN) {
    when(io.axi.wt.valid & io.axi.wt.ready) {
      when((aligned === false.B) & overStep) {
        when(rwLen(0) === 1.U) {
          rwWtData := io.rw.wdata(AxiDataWidth - 1, 0) >> alignedOffsetLow
        }.otherwise {
          rwWtData := io.rw.wdata(AxiDataWidth - 1, 0) << alignedOffsetHig
        }
      }.elsewhen(rwLen === i.U) {
        rwWtData := io.rw.wdata((i + 1) * AxiDataWidth - 1, i * AxiDataWidth)
      }
    }
  }

  io.axi.wt.data   := rwWtData
  io.axi.wtb.ready := (wtOperState === eumWtRESP)

  // ------------------Read Transaction------------------
  io.axi.ar.valid := WireDefault(rdOperState === eumRdADDR)
  io.axi.ar.addr  := axiAddr
  io.axi.ar.prot  := AXI_PROT_UNPRIVILEGED_ACCESS | AXI_PROT_SECURE_ACCESS | AXI_PROT_DATA_ACCESS
  io.axi.ar.id    := axiId
  io.axi.ar.user  := axiUser
  io.axi.ar.len   := axiLen
  io.axi.ar.size  := axiSize
  io.axi.ar.burst := AXI_BURST_TYPE_INCR
  io.axi.ar.lock  := 0.U
  io.axi.ar.cache := AXI_ARCACHE_NORMAL_NON_CACHEABLE_NON_BUFFERABLE
  io.axi.ar.qos   := 0.U

  // TODO: maybe assign to eumXXX directly?
  io.axi.rd.ready := WireDefault(rdOperState === eumRdREAD)

  //data transfer
  protected val axiRdDataLow: UInt = Wire(UInt(AxiDataWidth.W))
  axiRdDataLow := (io.axi.rd.data & maskLow) >> alignedOffsetLow
  protected val axiRdDataHig: UInt = Wire(UInt(AxiDataWidth.W))
  axiRdDataHig := (io.axi.rd.data & maskHig) << alignedOffsetHig

  // because now the TRANS_LEN is 1, so the data is one-dens
  protected val rwRdData: UInt = RegInit(0.U(AxiDataWidth.W))
  io.rw.rdata := rwRdData

  for (i <- 0 until TRANS_LEN) {
    when(io.axi.r.valid & io.axi.r.ready) {
      when((aligned === false.B) & overStep) {
        when(rwLen(0) === 1.U) {
          rwRdData(0) := rwRdData(0) | axiRdDataHig
        }.otherwise {
          rwRdData(0) := axiRdDataLow
        }
      }.elsewhen(rwLen === i.U) {
        rwRdData(i) := axiRdDataLow
      }
    }
  }
}
