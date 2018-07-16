package millfork.assembly.z80.opt

import millfork.assembly.OptimizationContext
import millfork.assembly.opt.SingleStatus
import millfork.assembly.z80.{OneRegister, TwoRegisters, ZLine}
import millfork.env._
import millfork.node.ZRegister

/**
  * @author Karol Stasiak
  */

class VariableStatus(val paramVariables: Set[String],
                     val stillUsedVariables: Set[String],
                     val variablesWithAddressesTaken: Set[String],
                     val variablesWithRegisterHint: Set[String],
                     val localVariables: List[Variable],
                     val variablesWithLifetimes: List[(Variable, Range)],
                     val variablesWithLifetimesMap: Map[String, Range],
                     val codeWithFlow: List[(FlowInfo, ZLine)])

object VariableStatus {
  def apply(f: NormalFunction, code: List[ZLine], optimizationContext: OptimizationContext, typFilter: Type => Boolean): Option[VariableStatus] = {
    val flow = FlowAnalyzer.analyze(f, code, optimizationContext.options, FlowInfoRequirement.BothFlows)
    import millfork.node.ZRegister._
    val paramVariables = f.params match {
      //      case NormalParamSignature(List(MemoryVariable(_, typ, _))) if typ.size == 1 =>
      //        Set[String]()
      case NormalParamSignature(ps) =>
        ps.map(_.name).toSet
      case _ =>
        // assembly functions do not get this optimization
        return None
    }
    val stillUsedVariables = code.flatMap {
      case ZLine(_, TwoRegisters(MEM_ABS_8 | MEM_ABS_16, _), MemoryAddressConstant(th), _) => Some(th.name)
      case ZLine(_, TwoRegisters(_, MEM_ABS_8 | MEM_ABS_16), MemoryAddressConstant(th), _) => Some(th.name)
      case ZLine(_, TwoRegisters(_, IMM_16), MemoryAddressConstant(th), _) => Some(th.name)
      case ZLine(_, TwoRegisters(MEM_ABS_8 | MEM_ABS_16, _), CompoundConstant(MathOperator.Plus, MemoryAddressConstant(th), NumericConstant(_, _)), _) => Some(th.name)
      case ZLine(_, TwoRegisters(_, MEM_ABS_8 | MEM_ABS_16), CompoundConstant(MathOperator.Plus, MemoryAddressConstant(th), NumericConstant(_, _)), _) => Some(th.name)
      case ZLine(_, TwoRegisters(_, IMM_16), CompoundConstant(MathOperator.Plus, MemoryAddressConstant(th), NumericConstant(_, _)), _) => Some(th.name)
      case _ => None
    }.toSet
    val variablesWithAddressesTaken = code.zipWithIndex.flatMap {
      case (ZLine(_, _, SubbyteConstant(MemoryAddressConstant(th), _), _), _) =>
        Some(th.name)
      case (ZLine(_, _, SubbyteConstant(CompoundConstant(MathOperator.Plus, MemoryAddressConstant(th), NumericConstant(_, _)), _), _), _) =>
        Some(th.name)
      case (ZLine(_,
      TwoRegisters(ZRegister.MEM_HL, _) | TwoRegisters(_, ZRegister.MEM_HL) | OneRegister(ZRegister.MEM_HL),
      _, _), i) =>
        flow(i)._1.statusBefore.hl match {
          case SingleStatus(MemoryAddressConstant(th)) =>
            if (flow(i)._1.importanceAfter.hlNumeric != Unimportant) Some(th.name)
            else None
          case SingleStatus(CompoundConstant(MathOperator.Plus, MemoryAddressConstant(th), NumericConstant(_, _))) =>
            if (flow(i)._1.importanceAfter.hlNumeric != Unimportant) Some(th.name)
            else None
          case _ => None // TODO: ???
        }
      case _ => None
    }.toSet
    val allLocalVariables = f.environment.getAllLocalVariables
    val localVariables = allLocalVariables.filter {
      case MemoryVariable(name, typ, VariableAllocationMethod.Auto | VariableAllocationMethod.Zeropage) =>
        typFilter(typ) && !paramVariables(name) && stillUsedVariables(name) && !variablesWithAddressesTaken(name)
      case _ => false
    }
    val variablesWithRegisterHint = f.environment.getAllLocalVariables.filter {
      case MemoryVariable(name, typ, VariableAllocationMethod.Register) =>
        typFilter(typ) && (typ.size == 1 || typ.size == 2) && !paramVariables(name) && stillUsedVariables(name) && !variablesWithAddressesTaken(name)
      case _ => false
    }.map(_.name).toSet
    val variablesWithLifetimes = localVariables.map(v =>
      v -> VariableLifetime.apply(v.name, flow)
    )
    val variablesWithLifetimesMap = variablesWithLifetimes.map {
      case (v, lt) => v.name -> lt
    }.toMap
    Some(new VariableStatus(
      paramVariables,
      stillUsedVariables,
      variablesWithAddressesTaken,
      variablesWithRegisterHint,
      localVariables,
      variablesWithLifetimes,
      variablesWithLifetimesMap,
      flow))
  }

}