package millfork.test

import millfork.test.emu.EmuUnoptimizedRun
import org.scalatest.{FunSuite, Matchers}

/**
  * @author Karol Stasiak
  */
class SinSuite extends FunSuite with Matchers {

  test("Compile-time sin") {
    val m = EmuUnoptimizedRun(
      """
        | array reference @$c000 =[
        |    0x80, 0x7d, 0x7a, 0x77, 0x74, 0x70, 0x6d, 0x6a,
        |    0x67, 0x64, 0x61, 0x5e, 0x5b, 0x58, 0x55, 0x52,
        |    0x4f, 0x4d, 0x4a, 0x47, 0x44, 0x41, 0x3f, 0x3c,
        |    0x39, 0x37, 0x34, 0x32, 0x2f, 0x2d, 0x2b, 0x28,
        |    0x26, 0x24, 0x22, 0x20, 0x1e, 0x1c, 0x1a, 0x18,
        |    0x16, 0x15, 0x13, 0x11, 0x10, 0x0f, 0x0d, 0x0c,
        |    0x0b, 0x0a, 0x08, 0x07, 0x06, 0x06, 0x05, 0x04,
        |    0x03, 0x03, 0x02, 0x02, 0x02, 0x01, 0x01, 0x01,
        |    0x01, 0x01, 0x01, 0x01, 0x02, 0x02, 0x02, 0x03,
        |    0x03, 0x04, 0x05, 0x06, 0x06, 0x07, 0x08, 0x0a,
        |    0x0b, 0x0c, 0x0d, 0x0f, 0x10, 0x11, 0x13, 0x15,
        |    0x16, 0x18, 0x1a, 0x1c, 0x1e, 0x20, 0x22, 0x24,
        |    0x26, 0x28, 0x2b, 0x2d, 0x2f, 0x32, 0x34, 0x37,
        |    0x39, 0x3c, 0x3f, 0x41, 0x44, 0x47, 0x4a, 0x4d,
        |    0x4f, 0x52, 0x55, 0x58, 0x5b, 0x5e, 0x61, 0x64,
        |    0x67, 0x6a, 0x6d, 0x70, 0x74, 0x77, 0x7a, 0x7d,
        |    0x80, 0x83, 0x86, 0x89, 0x8c, 0x90, 0x93, 0x96,
        |    0x99, 0x9c, 0x9f, 0xa2, 0xa5, 0xa8, 0xab, 0xae,
        |    0xb1, 0xb3, 0xb6, 0xb9, 0xbc, 0xbf, 0xc1, 0xc4,
        |    0xc7, 0xc9, 0xcc, 0xce, 0xd1, 0xd3, 0xd5, 0xd8,
        |    0xda, 0xdc, 0xde, 0xe0, 0xe2, 0xe4, 0xe6, 0xe8,
        |    0xea, 0xeb, 0xed, 0xef, 0xf0, 0xf1, 0xf3, 0xf4,
        |    0xf5, 0xf6, 0xf8, 0xf9, 0xfa, 0xfa, 0xfb, 0xfc,
        |    0xfd, 0xfd, 0xfe, 0xfe, 0xfe, 0xff, 0xff, 0xff,
        |    0xff, 0xff, 0xff, 0xff, 0xfe, 0xfe, 0xfe, 0xfd,
        |    0xfd, 0xfc, 0xfb, 0xfa, 0xfa, 0xf9, 0xf8, 0xf6,
        |    0xf5, 0xf4, 0xf3, 0xf1, 0xf0, 0xef, 0xed, 0xeb,
        |    0xea, 0xe8, 0xe6, 0xe4, 0xe2, 0xe0, 0xde, 0xdc,
        |    0xda, 0xd8, 0xd5, 0xd3, 0xd1, 0xce, 0xcc, 0xc9,
        |    0xc7, 0xc4, 0xc1, 0xbf, 0xbc, 0xb9, 0xb6, 0xb3,
        |    0xb1, 0xae, 0xab, 0xa8, 0xa5, 0xa2, 0x9f, 0x9c,
        |    0x99, 0x96, 0x93, 0x90, 0x8c, 0x89, 0x86, 0x83
        | ]
        | array alternate @$c100 = for i,0,until,256 [$80 - sin(i,$7f)]
        | void main () {
        | }
      """.stripMargin)
    for (i <- 0 until 256) {
      println(i)
      m.readByte(0xc100 + i) should equal(m.readByte(0xc000 + i))
    }
  }
}
