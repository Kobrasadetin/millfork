package millfork.test

import millfork.Cpu
import millfork.test.emu.{EmuCmosBenchmarkRun, EmuCrossPlatformBenchmarkRun, EmuZ80BenchmarkRun}
import org.scalatest.{FunSuite, Matchers}

/**
  * @author Karol Stasiak
  */
class StackVarSuite extends FunSuite with Matchers {

  test("Basic stack assignment") {
    EmuCrossPlatformBenchmarkRun(Cpu.Mos, Cpu.Z80, Cpu.Intel8080, Cpu.Sharp)("""
        | byte output @$c000
        | void main () {
        |   stack byte a
        |   stack byte b
        |   b = 4
        |   a = b
        |   output = a
        |   a = output
        | }
      """.stripMargin)(_.readByte(0xc000) should equal(4))
  }

  test("Stack byte addition") {
    EmuCrossPlatformBenchmarkRun(Cpu.Mos, Cpu.Z80, Cpu.Intel8080, Cpu.Sharp)("""
        | byte output @$c000
        | void main () {
        |   stack byte a
        |   stack byte b
        |   a = $11
        |   b = $44
        |   b += zzz()
        |   b += a
        |   output = b
        | }
        | byte zzz() {
        |   return $22
        | }
      """.stripMargin)(_.readWord(0xc000) should equal(0x77))
  }

  test("Complex expressions involving stack variables (6502)") {
    EmuCmosBenchmarkRun("""
        | byte output @$c000
        | void main () {
        |   stack byte a
        |   a = 7
        |   output = f(a) + f(a) + f(a)
        | }
        | asm byte f(byte a) {
        |   rts
        | }
      """.stripMargin)(_.readWord(0xc000) should equal(21))
  }

  test("Complex expressions involving stack variables (Z80)") {
    EmuCrossPlatformBenchmarkRun(Cpu.Z80, Cpu.Intel8080, Cpu.Sharp)("""
        | byte output @$c000
        | void main () {
        |   stack byte a
        |   a = 7
        |   output = f(a) + f(a) + f(a)
        | }
        | asm byte f(byte a) {
        |   ret
        | }
      """.stripMargin)(_.readWord(0xc000) should equal(21))
  }

  // ERROR: (8:9) Right-hand-side expression is too complex
//  test("Stack byte subtraction") {
//    EmuUnoptimizedRun("""
//        | byte output @$c000
//        | void main () {
//        |   stack byte a
//        |   stack byte b
//        |   b = $77
//        |   a = $11
//        |   b -= zzz()
//        |   b -= a
//        |   output = b
//        | }
//        | byte zzz() {
//        |   return $22
//        | }
//      """.stripMargin).readByte(0xc000) should equal(0x44)
//  }

  test("Stack word addition") {
    EmuCrossPlatformBenchmarkRun(Cpu.Mos, Cpu.Z80, Cpu.Intel8080, Cpu.Sharp)("""
        | word output @$c000
        | void main () {
        |   stack word a
        |   stack word b
        |   a = $111
        |   b = $444
        |   b += zzz()
        |   b += a
        |   output = b
        | }
        | word zzz() {
        |   return $222
        | }
      """.stripMargin)(_.readWord(0xc000) should equal(0x777))
  }

  test("Recursion") {
    EmuCrossPlatformBenchmarkRun(Cpu.Mos, Cpu.Z80, Cpu.Intel8080, Cpu.Sharp)("""
        | array output [6] @$c000
        | byte fails @$c010
        | void main () {
        |   word w
        |   byte i
        |   for i,0,until,output.length {
        |     w = fib(i)
        |     if w.hi != 0 { fails += 1 }
        |     output[i] = w.lo
        |   }
        | }
        | word fib(byte i) {
        |   stack byte j
        |   j = i
        |   if j < 2 {
        |     return 1
        |   }
        |   stack word sum
        |   sum = fib(j-1)
        |   sum += fib(j-2)
        |   sum &= $0F3F
        |   return sum
        | }
      """.stripMargin){ m =>
      m.readByte(0xc010) should equal(0)
      m.readByte(0xc000) should equal(1)
      m.readByte(0xc001) should equal(1)
      m.readByte(0xc002) should equal(2)
      m.readByte(0xc003) should equal(3)
      m.readByte(0xc004) should equal(5)
      m.readByte(0xc005) should equal(8)
    }
  }

  test("Recursion 2") {
    EmuCrossPlatformBenchmarkRun(Cpu.Mos, Cpu.Z80, Cpu.Intel8080, Cpu.Sharp)("""
        | array output [6] @$c000
        | byte fails @$c010
        | void main () {
        |   word w
        |   byte i
        |   for i,0,until,output.length {
        |     w = fib(i)
        |     if w.hi != 0 { fails += 1 }
        |     output[i] = w.lo
        |   }
        | }
        | word fib(byte i) {
        |   stack byte j
        |   j = i
        |   if j < 2 {
        |     return 1
        |   }
        |   return fib(j-1) + fib(j-2)
        | }
      """.stripMargin){ m =>
      m.readByte(0xc010) should equal(0)
      m.readByte(0xc000) should equal(1)
      m.readByte(0xc001) should equal(1)
      m.readByte(0xc002) should equal(2)
      m.readByte(0xc003) should equal(3)
      m.readByte(0xc004) should equal(5)
      m.readByte(0xc005) should equal(8)
    }
  }

  test("Complex stack-related stuff") {
    EmuCrossPlatformBenchmarkRun(Cpu.Mos, Cpu.Z80, Cpu.Intel8080, Cpu.Sharp)("""
        | byte output @$c000
        | array id = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9]
        | void main() {
        |   stack byte i
        |   i = b($4)
        |   output = b(id[b($2)]|id[b(i)])
        | }
        | noinline byte b(byte a) {
        |   return a
        | }
      """.stripMargin){ m =>
      m.readByte(0xc000) should equal(6)
    }
  }


  test("Indexing") {
    EmuCrossPlatformBenchmarkRun(Cpu.Mos, Cpu.Z80, Cpu.Intel8080, Cpu.Sharp)("""
        | array output [200] @$c000
        | void main () {
        |   stack byte a
        |   stack byte b
        |   a = $11
        |   b = $44
        |   output[a + b] = $66
        | }
      """.stripMargin){m => m.readWord(0xc055) should equal(0x66) }
  }

    test("Double array with stack variables") {
      EmuCrossPlatformBenchmarkRun(Cpu.Mos, Cpu.Z80, Cpu.Intel8080, Cpu.Sharp)(
        """
          | array output[5]@$c001
          | array input = [0,1,4,9,16,25,36,49]
          | void main () {
          |   stack byte i
          |   for i,0,until,output.length {
          |     output[i] = input[i+1]<<1
          |   }
          | }
          | void _panic(){while(true){}}
        """.stripMargin){ m=>
        m.readByte(0xc001) should equal (2)
        m.readByte(0xc005) should equal (50)
      }
    }
}
