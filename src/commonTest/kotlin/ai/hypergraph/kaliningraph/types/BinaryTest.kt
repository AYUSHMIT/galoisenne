package ai.hypergraph.kaliningraph.types

import kotlin.test.Test
import kotlin.test.assertEquals

class BinaryTest {
    @Test
    fun binaryTest() {
      val fifteen = T.plus1()
        .plus1()
        .plus1()
        .plus1()
        .plus1()
        .plus1()
        .plus1()
        .plus1()
        .plus1()
        .plus1()
        .plus1()
        .plus1()
        .plus1()
        .plus1().toInt()

      assertEquals(15, fifteen.toInt())
    }

  @Test
  fun ltrTest() {
    @Test
    fun binaryTest() {
      val fifteen = T.T.T.T

      assertEquals(15, fifteen.toInt())
    }
  }
}