package treecorel2

import chisel3._
import chisel3.util._

class IF2IDIO extends Bundle {
  val valid = Output(Bool())
  val inst  = Output(UInt(32.W))
  val pc    = Output(UInt(64.W))
}
