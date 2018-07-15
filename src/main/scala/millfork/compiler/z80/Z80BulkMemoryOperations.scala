package millfork.compiler.z80

import millfork.CompilationFlag
import millfork.assembly.z80._
import millfork.compiler.CompilationContext
import millfork.env._
import millfork.node._
import millfork.assembly.z80.ZOpcode._

import scala.collection.mutable

/**
  * @author Karol Stasiak
  */
object Z80BulkMemoryOperations {
  import Z80StatementCompiler.compileForStatement

  def compileMemcpy(ctx: CompilationContext, target: IndexedExpression, source: IndexedExpression, f: ForStatement): List[ZLine] = {
    val sourceOffset = removeVariableOnce(f.variable, source.index).getOrElse(return compileForStatement(ctx, f))
    if (!sourceOffset.isPure) return compileForStatement(ctx, f)
    val sourceIndexExpression = SumExpression(List(false -> sourceOffset, false -> f.start), decimal = false)
    val calculateSource = Z80ExpressionCompiler.calculateAddressToHL(ctx, IndexedExpression(source.name, sourceIndexExpression))
    compileMemoryBulk(ctx, target, f,
      useDEForTarget = true,
      preferDecreasing = false,
      _ => calculateSource -> Nil,
      next => List(
        ZLine.ld8(ZRegister.A, ZRegister.MEM_HL),
        ZLine.register(next, ZRegister.HL)
      ),
      decreasing => Some(if (decreasing) LDDR else LDIR)
    )
  }


  def compileMemset(ctx: CompilationContext, target: IndexedExpression, source: Expression, f: ForStatement): List[ZLine] = {
    val loadA = Z80ExpressionCompiler.stashHLIfChanged(Z80ExpressionCompiler.compileToA(ctx, source)) :+ ZLine.ld8(ZRegister.MEM_HL, ZRegister.A)
    compileMemoryBulk(ctx, target, f,
      useDEForTarget = false,
      preferDecreasing = false,
      _ => Nil -> Nil,
      _ => loadA,
      _ => None
    )
  }

  def compileMemtransform(ctx: CompilationContext, target: IndexedExpression, operator: String, source: Expression, f: ForStatement): List[ZLine] = {
    val c = determineExtraLoopRegister(ctx, f, source.containsVariable(f.variable))
    val load = buildMemtransformLoader(ctx, ZRegister.MEM_HL, f.variable, operator, source, c.loopRegister).getOrElse(return compileForStatement(ctx, f))
    import scala.util.control.Breaks._
    breakable{
      return compileMemoryBulk(ctx, target, f,
        useDEForTarget = false,
        preferDecreasing = true,
        isSmall => if (isSmall) Nil -> c.initC else break,
        _ => load ++ c.nextC,
        _ => None
      )
    }
    compileForStatement(ctx, f)
  }

  def compileMemtransform2(ctx: CompilationContext,
                           target1: IndexedExpression, operator1: String, source1: Expression,
                           target2: IndexedExpression, operator2: String, source2: Expression,
                           f: ForStatement): List[ZLine] = {
    import scala.util.control.Breaks._
    val c = determineExtraLoopRegister(ctx, f, source1.containsVariable(f.variable) || source2.containsVariable(f.variable))
    val target1Offset = removeVariableOnce(f.variable, target2.index).getOrElse(return compileForStatement(ctx, f))
    val target2Offset = removeVariableOnce(f.variable, target2.index).getOrElse(return compileForStatement(ctx, f))
    val target1IndexExpression = if (c.countDownDespiteSyntax) {
      SumExpression(List(false -> target1Offset, false -> f.end, true -> LiteralExpression(1, 1)), decimal = false)
    } else {
      SumExpression(List(false -> target1Offset, false -> f.start), decimal = false)
    }
    val target2IndexExpression = if (c.countDownDespiteSyntax) {
      SumExpression(List(false -> target2Offset, false -> f.end, true -> LiteralExpression(1, 1)), decimal = false)
    } else {
      SumExpression(List(false -> target2Offset, false -> f.start), decimal = false)
    }
    val fused = target1.name == target2.name && ((ctx.env.eval(target1Offset), ctx.env.eval(target2Offset)) match {
      case (Some(a), Some(b)) => a == b
      case _ => false
    })
    if (fused) {
      val load1 = buildMemtransformLoader(ctx, ZRegister.MEM_HL, f.variable, operator1, source1, c.loopRegister).getOrElse(return compileForStatement(ctx, f))
      val load2 = buildMemtransformLoader(ctx, ZRegister.MEM_HL, f.variable, operator2, source2, c.loopRegister).getOrElse(return compileForStatement(ctx, f))
      val loads = load1 ++ load2
      breakable{
        return compileMemoryBulk(ctx, target1, f,
          useDEForTarget = false,
          preferDecreasing = true,
          isSmall => if (isSmall) Nil -> c.initC else break,
          _ => loads ++ c.nextC,
          _ => None
        )
      }
    } else {
      val goodness1 = goodnessForHL(ctx, operator1, source1)
      val goodness2 = goodnessForHL(ctx, operator2, source2)
      val loads = if (goodness1 <= goodness2) {
        val load1 = buildMemtransformLoader(ctx, ZRegister.MEM_DE, f.variable, operator1, source1, c.loopRegister).getOrElse(return compileForStatement(ctx, f))
        val load2 = buildMemtransformLoader(ctx, ZRegister.MEM_HL, f.variable, operator2, source2, c.loopRegister).getOrElse(return compileForStatement(ctx, f))
        load1 ++ load2
      } else {
        val load1 = buildMemtransformLoader(ctx, ZRegister.MEM_HL, f.variable, operator1, source1, c.loopRegister).getOrElse(return compileForStatement(ctx, f))
        val load2 = buildMemtransformLoader(ctx, ZRegister.MEM_DE, f.variable, operator2, source2, c.loopRegister).getOrElse(return compileForStatement(ctx, f))
        load1 ++ load2
      }
      val targetForDE = if (goodness1 <= goodness2) target1 else target2
      val targetForHL = if (goodness1 <= goodness2) target2 else target1
      val targetForHLIndexExpression = if (goodness1 <= goodness2) target2IndexExpression else target1IndexExpression
      breakable{
        return compileMemoryBulk(ctx, targetForDE, f,
          useDEForTarget = true,
          preferDecreasing = true,
          isSmall => if (isSmall) {
            Z80ExpressionCompiler.calculateAddressToHL(ctx, IndexedExpression(targetForHL.name, targetForHLIndexExpression)) -> c.initC
          } else break,
          next => loads ++ (c.nextC :+ ZLine.register(next, ZRegister.HL)),
          _ => None
        )
      }
    }
    compileForStatement(ctx, f)
  }

  private case class ExtraLoopRegister(loopRegister: ZRegister.Value, initC: List[ZLine], nextC: List[ZLine], countDownDespiteSyntax: Boolean)

  private def determineExtraLoopRegister(ctx: CompilationContext, f: ForStatement, readsLoopVariable: Boolean): ExtraLoopRegister = {
    val useC = readsLoopVariable && (f.direction match {
      case ForDirection.Until | ForDirection.To => true
      case ForDirection.DownTo => ctx.env.eval(f.end) match {
        case Some(NumericConstant(1, _)) => false
        case _ => true
      }
      case ForDirection.ParallelUntil | ForDirection.ParallelTo => ctx.env.eval(f.start) match {
        case Some(NumericConstant(1, _)) => false
        case _ => true
      }
    })
    val loopRegister = if (useC) ZRegister.C else ZRegister.B
    val countDown = f.direction == ForDirection.ParallelTo || f.direction == ForDirection.ParallelUntil || f.direction == ForDirection.DownTo
    val countDownDespiteSyntax = f.direction == ForDirection.ParallelTo || f.direction == ForDirection.ParallelUntil
    val initC = if (useC) Z80ExpressionCompiler.compileToA(ctx, f.direction match {
      case ForDirection.ParallelTo => f.end
      case ForDirection.ParallelUntil => SumExpression(List(false -> f.end, true -> LiteralExpression(1, 1)), decimal = false)
      case _ => f.start
    }) :+ ZLine.ld8(ZRegister.C, ZRegister.A) else Nil
    val nextC = if (useC) List(ZLine.register(if (countDown) DEC else INC, ZRegister.C)) else Nil
    ExtraLoopRegister(loopRegister, initC, nextC, countDownDespiteSyntax)
  }

  private def goodnessForHL(ctx: CompilationContext, operator: String, source: Expression): Int = operator match {
    case "<<=" | ">>=" =>
      if (ctx.options.flag(CompilationFlag.EmitExtended80Opcodes)) ctx.env.eval(source) match {
        case Some(NumericConstant(n, _)) =>
          if (ctx.options.flag(CompilationFlag.OptimizeForSize)) {
            2
          } else {
            // SLA (HL) = 15 cycles
            // SLA A = 8 cycles
            // LD A,(HL) + LD (HL),A = 14 cycles
            (14 - 7 * (n max 0).toInt).max(0)
          }
        case _ => 0
      } else 0
    case "+=" | "-=" => ctx.env.eval(source) match {
      case Some(NumericConstant(1, _)) =>
        if (ctx.options.flag(CompilationFlag.OptimizeForSize)) {
          2
        } else {
          // INC (HL) = 11 cycles
          // INC A = 4 cycles
          // LD A,(HL) + LD (HL),A = 14 cycles
          7
        }
      case Some(NumericConstant(2, _)) =>
        if (ctx.options.flag(CompilationFlag.OptimizeForSize)) {
          2
        } else {
          // INC (HL) = 11 cycles
          // ADD # = 7 cycles
          // LD A,(HL) + LD (HL),A = 14 cycles
          0
        }
      case _ => 0
    }
    case "=" => ctx.env.eval(source) match {
      case Some(_) =>
        if (ctx.options.flag(CompilationFlag.OptimizeForSize)) {
          1
        } else {
          // LD (HL),# = 11 cycles
          // LD A,# = 4 cycles
          // LD (HL),A = 7 cycles
          // so we don't save cycles, but we do save one byte
          1
        }
      case _ => 0
    }
    case _ => 0
  }

  private def buildMemtransformLoader(ctx: CompilationContext, element: ZRegister.Value, loopVariable: String, operator: String, source: Expression, loopRegister: ZRegister.Value): Option[List[ZLine]] = {
    val env = ctx.env
    if (operator == "=") {
      source match {
        case VariableExpression(n) if n == loopVariable =>
          if (element == ZRegister.MEM_HL) Some(List(ZLine.ld8(ZRegister.MEM_HL, loopRegister)))
          else Some(List(ZLine.ld8(ZRegister.A, loopRegister), ZLine.ld8(element, ZRegister.A)))
        case _ => env.eval(source) map { c =>
          if (element == ZRegister.MEM_HL) List(ZLine.ldImm8(ZRegister.MEM_HL, c))
          else List(ZLine.ldImm8(ZRegister.A, c), ZLine.ld8(element, ZRegister.A))
        }
      }
    } else {
      val (operation, daa, shift) = operator match {
        case "+=" => (ZOpcode.ADD, false, None)
        case "+'=" => (ZOpcode.ADD, true, None)
        case "-=" => (ZOpcode.SUB, false, None)
        case "-'=" => (ZOpcode.SUB, true, None)
        case "|=" => (ZOpcode.OR, false, None)
        case "&=" => (ZOpcode.AND, false, None)
        case "^=" => (ZOpcode.XOR, false, None)
        case "<<=" => (ZOpcode.SLA, false, Some(RL, 0xfe))
        case ">>=" => (ZOpcode.SRL, false, Some(RR, 0x7f))
        case _ => return None
      }
      shift match {
        case Some((nonZ80, mask)) =>
          Some(env.eval(source) match {
            case Some(NumericConstant(n, _)) =>
              if (n <= 0) Nil else {
                if (ctx.options.flag(CompilationFlag.EmitExtended80Opcodes)) {
                  if (element == ZRegister.MEM_HL && n <= 2) {
                    List.fill(n.toInt)(ZLine.register(operation, ZRegister.MEM_HL))
                  } else {
                    val builder = mutable.ListBuffer[ZLine]()
                    builder += ZLine.ld8(ZRegister.A, element)
                    for (_ <- 0 until n.toInt) {
                      builder += ZLine.register(operation, ZRegister.A)
                    }
                    builder += ZLine.ld8(element, ZRegister.A)
                    builder.toList
                  }
                } else {
                  val builder = mutable.ListBuffer[ZLine]()
                  builder += ZLine.ld8(ZRegister.A, element)
                  for (_ <- 0 until n.toInt) {
                    builder += ZLine.register(nonZ80, ZRegister.A)
                    builder += ZLine.imm8(AND, mask)
                  }
                  builder += ZLine.ld8(element, ZRegister.A)
                  builder.toList
                }
              }
            case _ => return None
          })
        case None =>
          val mod = source match {
            case VariableExpression(n) if n == loopVariable =>
              List(ZLine.register(operation, loopRegister))
            case _ => env.eval(source) match {
              case Some(NumericConstant(1, _)) if operator == "+=" && element == ZRegister.MEM_HL =>
                return Some(List(ZLine.register(INC, ZRegister.MEM_HL)))
              case Some(NumericConstant(1, _)) if operator == "-=" && element == ZRegister.MEM_HL =>
                return Some(List(ZLine.register(DEC, ZRegister.MEM_HL)))
              case Some(NumericConstant(2, _)) if operator == "+=" && element == ZRegister.MEM_HL && ctx.options.flag(CompilationFlag.OptimizeForSize) =>
                return Some(List(ZLine.register(INC, ZRegister.MEM_HL), ZLine.register(INC, ZRegister.MEM_HL)))
              case Some(NumericConstant(2, _)) if operator == "-=" && element == ZRegister.MEM_HL && ctx.options.flag(CompilationFlag.OptimizeForSize) =>
                return Some(List(ZLine.register(DEC, ZRegister.MEM_HL), ZLine.register(DEC, ZRegister.MEM_HL)))
              case Some(c) =>
                if (daa) {
                  List(ZLine.imm8(operation, c), ZLine.implied(DAA))
                } else {
                  List(ZLine.imm8(operation, c))
                }
              case _ => return None
            }
          }
          Some(
            ZLine.ld8(ZRegister.A, element) :: (mod :+ ZLine.ld8(element, ZRegister.A))
          )
      }
    }
  }

  /**
    *
    * @param ctx                      compilation context
    * @param target                   target indexed expression
    * @param f                        original for statement
    * @param useDEForTarget           use DE instead of HL for target
    * @param extraAddressCalculations extra calculations to perform before the loop, before and after POP BC (parameter: is count small)
    * @param loadA                    byte value calculation (parameter: INC_16 or DEC_16)
    * @param z80Bulk                  Z80 opcode for faster operation (parameter: is decreasing)
    * @return
    */
  def compileMemoryBulk(ctx: CompilationContext,
                        target: IndexedExpression,
                        f: ForStatement,
                        useDEForTarget: Boolean,
                        preferDecreasing: Boolean,
                        extraAddressCalculations: Boolean => (List[ZLine], List[ZLine]),
                        loadA: ZOpcode.Value => List[ZLine],
                        z80Bulk: Boolean => Option[ZOpcode.Value]): List[ZLine] = {
    val one = LiteralExpression(1, 1)
    val targetOffset = removeVariableOnce(f.variable, target.index).getOrElse(return compileForStatement(ctx, f))
    if (!targetOffset.isPure) return compileForStatement(ctx, f)
    val indexVariableSize = ctx.env.get[Variable](f.variable).typ.size
    val wrapper = createForLoopPreconditioningIfStatement(ctx, f)
    val decreasingDespiteSyntax = preferDecreasing && (f.direction == ForDirection.ParallelTo || f.direction == ForDirection.ParallelUntil)
    val decreasing = f.direction == ForDirection.DownTo || decreasingDespiteSyntax
    val plusOne = f.direction == ForDirection.To || f.direction == ForDirection.DownTo || f.direction == ForDirection.ParallelTo
    val byteCountExpression =
      if (f.direction == ForDirection.DownTo) SumExpression(List(false -> f.start, false -> one, true -> f.end), decimal = false)
      else if (plusOne) SumExpression(List(false -> f.end, false -> one, true -> f.start), decimal = false)
      else SumExpression(List(false -> f.end, true -> f.start), decimal = false)
    val targetIndexExpression = if (decreasingDespiteSyntax) {
      SumExpression(List(false -> targetOffset, false -> f.end, true -> one), decimal = false)
    } else {
      SumExpression(List(false -> targetOffset, false -> f.start), decimal = false)
    }
    val ldr = z80Bulk(decreasing)
    val smallCount = indexVariableSize == 1 && (ldr.isEmpty || !ctx.options.flag(CompilationFlag.EmitZ80Opcodes))
    val calculateByteCount = if (smallCount) {
      Z80ExpressionCompiler.compileToA(ctx, byteCountExpression) ++
        List(ZLine.ld8(ZRegister.B, ZRegister.A))
    } else {
      Z80ExpressionCompiler.compileToHL(ctx, byteCountExpression) ++
        List(ZLine.ld8(ZRegister.B, ZRegister.H), ZLine.ld8(ZRegister.C, ZRegister.L))
    }
    val next = if (decreasing) DEC_16 else INC_16
    val calculateSourceValue = loadA(next)
    val calculateTargetAddress = Z80ExpressionCompiler.calculateAddressToHL(ctx, IndexedExpression(target.name, targetIndexExpression))
    val extraInitializationPair = extraAddressCalculations(smallCount)
    // TODO: figure the optimal compilation order
    val loading = if (useDEForTarget) {
      calculateByteCount ++
        Z80ExpressionCompiler.stashBCIfChanged(calculateTargetAddress ++ List(ZLine.ld8(ZRegister.D, ZRegister.H), ZLine.ld8(ZRegister.E, ZRegister.L))) ++
        Z80ExpressionCompiler.stashBCIfChanged(Z80ExpressionCompiler.stashDEIfChanged(extraInitializationPair._1)) ++
        Z80ExpressionCompiler.stashHLIfChanged(Z80ExpressionCompiler.stashDEIfChanged(extraInitializationPair._2))
    } else {
      calculateByteCount ++
        Z80ExpressionCompiler.stashBCIfChanged(calculateTargetAddress) ++
        Z80ExpressionCompiler.stashBCIfChanged(Z80ExpressionCompiler.stashHLIfChanged(extraInitializationPair._1)) ++
        Z80ExpressionCompiler.stashHLIfChanged(extraInitializationPair._2)
    }

    val label = Z80Compiler.nextLabel("me")
    val body = if (ldr.isDefined && ctx.options.flag(CompilationFlag.EmitZ80Opcodes)) {
      List(ZLine.implied(ldr.get))
    } else {
      ZLine.label(label) :: calculateSourceValue ++ (if (smallCount) {
        List(
          ZLine.register(next, if (useDEForTarget) ZRegister.DE else ZRegister.HL),
          ZLine.djnz(label)
        )
      } else {
        List(
          ZLine.register(next, if (useDEForTarget) ZRegister.DE else ZRegister.HL),
          ZLine.register(DEC_16, ZRegister.BC),
          ZLine.ld8(ZRegister.A, ZRegister.C),
          ZLine.register(OR, ZRegister.B),
          ZLine.jump(label, IfFlagSet(ZFlag.Z))
        )
      })
    }
    wrapper.flatMap(l => if (l.opcode == NOP) loading ++ body else List(l))
  }

  private def createForLoopPreconditioningIfStatement(ctx: CompilationContext, f: ForStatement): List[ZLine] = {
    val operator = f.direction match {
      case ForDirection.To | ForDirection.ParallelTo => "<="
      case ForDirection.DownTo => ">="
      case ForDirection.Until | ForDirection.ParallelUntil => "<"
    }
    Z80StatementCompiler.compile(ctx, IfStatement(
      FunctionCallExpression(operator, List(f.start, f.end)),
      List(Z80AssemblyStatement(ZOpcode.NOP, NoRegisters, None, LiteralExpression(0, 1), elidable = false)),
      Nil))
  }

  private def removeVariableOnce(variable: String, expr: Expression): Option[Expression] = {
    expr match {
      case VariableExpression(i) => if (i == variable) Some(LiteralExpression(0, 1)) else None
      case SumExpression(exprs, false) =>
        if (exprs.count(_._2.containsVariable(variable)) == 1) {
          Some(SumExpression(exprs.map {
            case (false, e) => false -> (if (e.containsVariable(variable)) removeVariableOnce(variable, e).getOrElse(return None) else e)
            case (true, e) => if (e.containsVariable(variable)) return None else true -> e
          }, decimal = false))
        } else None
      case _ => None
    }
  }

}