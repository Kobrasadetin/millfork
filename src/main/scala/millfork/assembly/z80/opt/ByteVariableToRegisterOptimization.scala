package millfork.assembly.z80.opt

import millfork.assembly.z80._
import millfork.assembly.{AssemblyOptimization, OptimizationContext}
import millfork.env._
import millfork.error.ErrorReporting
import millfork.node.ZRegister
import millfork.{CompilationFlag, NonOverlappingIntervals}

import scala.collection.mutable.ListBuffer

/**
  * @author Karol Stasiak
  */
object ByteVariableToRegisterOptimization extends AssemblyOptimization[ZLine] {

  override def name = "Allocating variables to single registers"

  object CyclesAndBytes {
    val Zero = CyclesAndBytes(0, 0)
  }

  case class CyclesAndBytes(bytes: Int, cycles: Int) {
    def +(that: CyclesAndBytes) = CyclesAndBytes(this.bytes + that.bytes, this.cycles + that.cycles)
  }

  override def optimize(f: NormalFunction, code: List[ZLine], optimizationContext: OptimizationContext): List[ZLine] = {
    val vs = VariableStatus(f, code, optimizationContext, _.size == 1).getOrElse(return code)
    val options = optimizationContext.options
    val removeVariablesForReal = !options.flag(CompilationFlag.InternalCurrentlyOptimizingForMeasurement)
    val costFunction: CyclesAndBytes => Int = if (options.flag(CompilationFlag.OptimizeForSpeed)) _.cycles else _.bytes

    val bCandidates = getCandidates(vs, _.b, ZRegister.B)
    val cCandidates = getCandidates(vs, _.c, ZRegister.C)
    val dCandidates = getCandidates(vs, _.d, ZRegister.D)
    val eCandidates = getCandidates(vs, _.e, ZRegister.E)
    val hCandidates = getCandidates(vs, _.h, ZRegister.H)
    val lCandidates = getCandidates(vs, _.l, ZRegister.L)

    val bCandidateSets = NonOverlappingIntervals.apply[(String, Range, CyclesAndBytes)](bCandidates, _._2.start, _._2.end)
    val cCandidateSets = NonOverlappingIntervals.apply[(String, Range, CyclesAndBytes)](cCandidates, _._2.start, _._2.end)
    val dCandidateSets = NonOverlappingIntervals.apply[(String, Range, CyclesAndBytes)](dCandidates, _._2.start, _._2.end)
    val eCandidateSets = NonOverlappingIntervals.apply[(String, Range, CyclesAndBytes)](eCandidates, _._2.start, _._2.end)
    val hCandidateSets = NonOverlappingIntervals.apply[(String, Range, CyclesAndBytes)](hCandidates, _._2.start, _._2.end)
    val lCandidateSets = NonOverlappingIntervals.apply[(String, Range, CyclesAndBytes)](lCandidates, _._2.start, _._2.end)

    val variants = for {
      vb <- if (options.flag(CompilationFlag.SingleThreaded)) bCandidateSets else bCandidateSets.par
      nb = vb.map(_._1)

      vc <- cCandidateSets
      nc = vc.map(_._1)
      if (nb & nc).isEmpty

      vd <- dCandidateSets
      nd = vd.map(_._1)
      if (nb & nd).isEmpty
      if (nc & nd).isEmpty

      ve <- eCandidateSets
      ne = ve.map(_._1)
      if (nb & ne).isEmpty
      if (nc & ne).isEmpty
      if (nd & ne).isEmpty

      vh <- hCandidateSets
      nh = vh.map(_._1)
      if (nb & nh).isEmpty
      if (nc & nh).isEmpty
      if (nd & nh).isEmpty
      if (ne & nh).isEmpty

      vl <- lCandidateSets
      nl = vl.map(_._1)
      if (nb & nl).isEmpty
      if (nc & nl).isEmpty
      if (nd & nl).isEmpty
      if (ne & nl).isEmpty
      if (nh & nl).isEmpty


      score = vb.toSeq.map(x => costFunction(x._3)).sum +
        vc.toSeq.map(x => costFunction(x._3)).sum +
        vd.toSeq.map(x => costFunction(x._3)).sum +
        ve.toSeq.map(x => costFunction(x._3)).sum +
        vh.toSeq.map(x => costFunction(x._3)).sum +
        vl.toSeq.map(x => costFunction(x._3)).sum
    } yield (score, vb, vc, vd, ve, vh, vl)

    if (variants.isEmpty) {
      return code
    }

    //    variants.foreach(println)

    val (_, bestBs, bestCs, bestDs, bestEs, bestHs, bestLs) = variants.maxBy(_._1)

    def reportOptimizedBlock[T](oldCode: List[(T, ZLine)], newCode: List[ZLine]): Unit = {
      oldCode.foreach(l => ErrorReporting.trace(l._2.toString))
      ErrorReporting.trace("     ↓")
      newCode.foreach(l => ErrorReporting.trace(l.toString))
    }

    if (bestBs.nonEmpty || bestCs.nonEmpty || bestDs.nonEmpty || bestEs.nonEmpty || bestHs.nonEmpty || bestLs.nonEmpty) {
      val output = ListBuffer[ZLine]()
      var i = 0
      @inline
      def tryInline(bests: Set[(String, Range, CyclesAndBytes)], register: ZRegister.Value): Boolean = {
        bests.find(_._2.start == i).exists {
          case (v, range, _) =>
            ErrorReporting.debug(s"Inlining $v to single register $register")
            val oldCode = vs.codeWithFlow.slice(range.start, range.end)
            val newCode = inlineVars(v, register, addressInHl = false, addressInBc = false, addressInDe = false, oldCode.map(_._2))
            reportOptimizedBlock(oldCode, newCode)
            output ++= newCode
            i = range.end
            if (removeVariablesForReal && contains(range, vs.variablesWithLifetimesMap(v))) {
              f.environment.removeVariable(v)
            }
            true
          case _ => false
        }
      }
      while (i < code.length) {
        var done = false
        done =  tryInline(bestBs, ZRegister.B)
        if (!done) {
          done =  tryInline(bestCs, ZRegister.C)
        }
        if (!done) {
          done =  tryInline(bestDs, ZRegister.D)
        }
        if (!done) {
          done =  tryInline(bestEs, ZRegister.E)
        }
        if (!done) {
          done =  tryInline(bestHs, ZRegister.H)
        }
        if (!done) {
          done =  tryInline(bestLs, ZRegister.L)
        }
        if (!done) {
          output += code(i)
          i += 1
        }
      }
      output.toList
    } else {
      code
    }
  }

  private def getCandidates(vs: VariableStatus, importanceExtractor: CpuImportance => Importance, register: ZRegister.Value) = {
    vs.variablesWithLifetimes.filter {
      case (v, range) =>
        val tuple = vs.codeWithFlow(range.start)
        importanceExtractor(tuple._1.importanceAfter) != Important || {
//          println(s"Cannot inline ${v.name} to $register because of early $tuple")
          false
        }
    }.flatMap {
      case (v, range) =>
        canBeInlined(v.name, synced = false, register, Some(false), Some(false), Some(false), vs.codeWithFlow.slice(range.start, range.end)).map { score =>
          (v.name, range, if (vs.variablesWithRegisterHint(v.name)) score + CyclesAndBytes(16, 16) else score)
        }
    }
  }

  def contains(outer: Range, inner: Range): Boolean = {
    outer.contains(inner.start) && outer.contains(inner.end - 1)
  }

  import millfork.assembly.z80.ZOpcode._
  import millfork.node.ZRegister._

  def add(first: Boolean, ifTrue: CyclesAndBytes, ifFalse: CyclesAndBytes): CyclesAndBytes=>CyclesAndBytes = { c =>
    if (first) c + ifTrue else c + ifFalse
  }
  def add(value: CyclesAndBytes): CyclesAndBytes=>CyclesAndBytes = _ + value

  def canBeInlined(vname: String, synced: Boolean, target: ZRegister.Value, addressInHl: Option[Boolean], addressInBc: Option[Boolean], addressInDe: Option[Boolean], code: List[(FlowInfo, ZLine)]): Option[CyclesAndBytes] = {
    def fail(reason: Int): None.type = {
//      println(s"Cannot inline $vname to $target because of [[$reason]] ${code.head}")
      None
    }
    object NotTarget {
      def unapply(r: ZRegister.Value): Option[ZRegister.Value] = if (r == target) None else Some(r)
    }
    object ThisVar {
      def unapply(c: Constant): Option[Constant] = c match {
        case MemoryAddressConstant(th) if th.name == vname => Some(c)
        case _ => None
      }
    }
    code match {
      case (_, ZLine(LD, TwoRegisters(A, MEM_ABS_8), ThisVar(_), _)) :: xs =>
        canBeInlined(vname, synced, target, addressInHl, addressInBc, addressInDe, xs).map(add(CyclesAndBytes(9, 2)))
      case (_, ZLine(LD, TwoRegisters(MEM_ABS_8, A), ThisVar(_), _)) :: xs =>
        canBeInlined(vname, synced, target, addressInHl, addressInBc, addressInDe, xs).map(add(CyclesAndBytes(9, 2)))

      case (_, ZLine(LD_16, TwoRegisters(HL, IMM_16), ThisVar(_), _)) :: xs =>
        if (target == H || target == L) fail(61) else
        canBeInlined(vname, synced, target, addressInHl = Some(true), addressInBc, addressInDe, xs).map(add(CyclesAndBytes(10, 3)))
      case (_, ZLine(LD_16, TwoRegisters(BC, IMM_16), ThisVar(_), _)) :: xs =>
        if (target == B || target == C) fail(61) else
        canBeInlined(vname, synced, target, addressInHl, addressInBc = Some(true), addressInDe, xs).map(add(CyclesAndBytes(10, 3)))
      case (_, ZLine(LD_16, TwoRegisters(DE, IMM_16), ThisVar(_), _)) :: xs =>
        if (target == D || target == E) fail(61) else
        canBeInlined(vname, synced, target, addressInHl, addressInBc, addressInDe = Some(true), xs).map(add(CyclesAndBytes(10, 3)))

      case (_, ZLine(_, OneRegister(MEM_HL), _, _)) :: xs => addressInHl match {
        case Some(true) => canBeInlined(vname, synced, target, addressInHl, addressInBc, addressInDe, xs).map(add(CyclesAndBytes(3, 0)))
        case Some(false) => canBeInlined(vname, synced, target, addressInHl, addressInBc, addressInDe, xs)
        case None => fail(70)
      }

      case (_, ZLine(LD, TwoRegisters(MEM_HL, NotTarget(_))  | TwoRegisters(NotTarget(_), MEM_HL), _, _)) :: xs => addressInHl match {
        case Some(true) => canBeInlined(vname, synced, target, addressInHl, addressInBc, addressInDe, xs).map(add(CyclesAndBytes(3, 0)))
        case Some(false) => canBeInlined(vname, synced, target, addressInHl, addressInBc, addressInDe, xs)
        case None => fail(71)
      }
      case (_, ZLine(LD, TwoRegisters(MEM_BC, NotTarget(_)) | TwoRegisters(NotTarget(_), MEM_BC), _, _)) :: xs => addressInBc match {
        case Some(true) => canBeInlined(vname, synced, target, addressInHl, addressInBc, addressInDe, xs).map(add(CyclesAndBytes(3, 0)))
        case Some(false) => canBeInlined(vname, synced, target, addressInHl, addressInBc, addressInDe, xs)
        case None => fail(72)
      }
      case (_, ZLine(LD, TwoRegisters(MEM_DE, NotTarget(_)) | TwoRegisters(NotTarget(_), MEM_DE), _, _)) :: xs => addressInDe match {
        case Some(true) => canBeInlined(vname, synced, target, addressInHl, addressInBc, addressInDe, xs).map(add(CyclesAndBytes(3, 0)))
        case Some(false) => canBeInlined(vname, synced, target, addressInHl, addressInBc, addressInDe, xs)
        case None => fail(73)
      }

      case (_, ZLine(_, _, SubbyteConstant(ThisVar(_), _), _)) :: _ => fail(6)
      case (_, ZLine(_, _, ThisVar(_), _)) :: _ => fail(4)
      case (_, ZLine(_, _, CompoundConstant(_, ThisVar(_), _), _)) :: _ => fail(5)
      case (_, x) :: xs if x.changesRegister(target) => fail(1)
      case (_, x) :: xs if x.readsRegister(target) && !synced => fail(2)

        // TODO: tossing addresses around:
      // case (_, ZLine(LD, TwoRegisters(H, B), _, _)) :: (_, ZLine(LD, TwoRegisters(L, C), _, _)) :: xs =>


      case (_, x) :: xs if x.changesRegister(HL) => canBeInlined(vname, synced, target, addressInHl = Some(false), addressInBc, addressInDe, xs)
      case (_, x) :: xs if x.changesRegister(BC) => canBeInlined(vname, synced, target, addressInHl, addressInBc = Some(false), addressInDe, xs)
      case (_, x) :: xs if x.changesRegister(DE) => canBeInlined(vname, synced, target, addressInHl, addressInBc, addressInDe = Some(false), xs)

      case (_, x) :: xs if x.readsRegister(HL) && addressInHl.contains(true) => fail(81)
      case (_, x) :: xs if x.readsRegister(BC) && addressInBc.contains(true) => fail(82)
      case (_, x) :: xs if x.readsRegister(DE) && addressInDe.contains(true) => fail(83)

      case (_, ZLine(LABEL, _, _, _)) :: xs => canBeInlined(vname, synced = false, target, addressInHl, addressInBc, addressInDe, xs)
      case (_, ZLine(CALL, _, _, _)) :: xs => fail(3)
      case _ :: xs => canBeInlined(vname, synced, target, addressInHl, addressInBc, addressInDe, xs)
      case _ => Some(CyclesAndBytes.Zero)
    }
  }

  def inlineVars(vname: String, target: ZRegister.Value, addressInHl: Boolean, addressInBc: Boolean, addressInDe: Boolean, code: List[ZLine]): List[ZLine] = {
    if (code.nonEmpty) println(code.head)
    code match {
      case ZLine(LD, TwoRegisters(A, MEM_ABS_8), MemoryAddressConstant(th), _) :: xs if th.name == vname =>
        ZLine.ld8(A, target) :: inlineVars(vname, target, addressInHl, addressInBc, addressInDe, xs)
      case ZLine(LD, TwoRegisters(MEM_ABS_8, A), MemoryAddressConstant(th), _) :: xs if th.name == vname =>
        ZLine.ld8(target, A) :: inlineVars(vname, target, addressInHl, addressInBc, addressInDe, xs)

      case  ZLine(LD_16, TwoRegisters(HL, IMM_16), MemoryAddressConstant(th), _) :: xs if th.name == vname =>
        inlineVars(vname, target, addressInHl = true, addressInBc, addressInDe, xs)
      case  ZLine(LD_16, TwoRegisters(BC, IMM_16), MemoryAddressConstant(th), _) :: xs if th.name == vname =>
        inlineVars(vname, target, addressInHl, addressInBc = true, addressInDe, xs)
      case ZLine(LD_16, TwoRegisters(DE, IMM_16), MemoryAddressConstant(th), _) :: xs if th.name == vname =>
        inlineVars(vname, target, addressInHl, addressInBc, addressInDe = true, xs)

      case (x@ZLine(_, OneRegister(MEM_HL), _, _)) :: xs if addressInHl =>
        x.copy(registers = OneRegister(target)) ::
        inlineVars(vname, target, addressInHl, addressInBc, addressInDe, xs)

      case (x@ZLine(LD, TwoRegisters(MEM_HL, reg), p, _)) :: xs if addressInHl =>
        x.copy(registers = TwoRegisters(target, reg), parameter = p) ::
          inlineVars(vname, target, addressInHl, addressInBc, addressInDe, xs)
      case (x@ZLine(LD, TwoRegisters(reg, MEM_HL), p, _)) :: xs if addressInHl =>
        x.copy(registers = TwoRegisters(reg, target), parameter = p) ::
          inlineVars(vname, target, addressInHl, addressInBc, addressInDe, xs)

      case (x@ZLine(LD, TwoRegisters(MEM_BC, reg), p, _)) :: xs if addressInBc =>
        x.copy(registers = TwoRegisters(target, reg), parameter = p) ::
          inlineVars(vname, target, addressInHl, addressInBc, addressInDe, xs)
      case (x@ZLine(LD, TwoRegisters(reg, MEM_BC), p, _)) :: xs if addressInBc =>
        x.copy(registers = TwoRegisters(reg, target), parameter = p) ::
          inlineVars(vname, target, addressInHl, addressInBc, addressInDe, xs)

      case (x@ZLine(LD, TwoRegisters(MEM_DE, reg), p, _)) :: xs if addressInDe =>
        x.copy(registers = TwoRegisters(target, reg), parameter = p) ::
          inlineVars(vname, target, addressInHl, addressInBc, addressInDe, xs)
      case (x@ZLine(LD, TwoRegisters(reg, MEM_DE), p, _)) :: xs if addressInDe =>
        x.copy(registers = TwoRegisters(reg, target), parameter = p) ::
          inlineVars(vname, target, addressInHl, addressInBc, addressInDe, xs)

      case x :: xs if x.changesRegister(HL) =>
        x :: inlineVars(vname, target, addressInHl = false, addressInBc, addressInDe, xs)
      case x :: xs if x.changesRegister(BC) =>
        x :: inlineVars(vname, target, addressInHl, addressInBc = false, addressInDe, xs)
      case x :: xs if x.changesRegister(DE) =>
        x :: inlineVars(vname, target, addressInHl, addressInBc, addressInDe = false, xs)

      case x :: _ if x.changesRegister(target) => ???
      case x :: xs => x :: inlineVars(vname, target, addressInHl, addressInBc, addressInDe, xs)
      case Nil => Nil
    }
  }
}
