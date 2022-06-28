package ai.hypergraph.kaliningraph.automata

import ai.hypergraph.kaliningraph.tensor.FreeMatrix
import ai.hypergraph.kaliningraph.tensor.transpose
import ai.hypergraph.kaliningraph.types.*
import kotlin.math.absoluteValue

typealias Context<A> = Π3<A, A, A>
val <A> Context<A>.p get() = π1
val <A> Context<A>.q get() = π2
val <A> Context<A>.r get() = π3

val ecaAlgebra = algebra()
fun makeVec(len: Int) =
  FreeMatrix(ecaAlgebra, len, 1) { r, c ->
    if (len - 1 == r) Context(null, true, null)
    else Context(null, false, null)
  }

// Create a tridiagonal matrix
fun FreeMatrix<Context<Boolean?>?>.genMat(): FreeMatrix<Context<Boolean?>?> =
  FreeMatrix(ecaAlgebra, numRows, numRows) { r, c ->
    if ((r - c).absoluteValue < 2) Context(null, null, null) else null
  }

tailrec fun FreeMatrix<Context<Boolean?>?>.evolve(
  rule: FreeMatrix<Context<Boolean?>?> = genMat(),
  steps: Int = 100,
  hashes: Set<Int> = emptySet(),
  hashCode: Int = str().hashCode()
): FreeMatrix<Context<Boolean?>?> =
  if (steps == 0 || hashCode in hashes) this.also { it.print() }
  else (rule * this.also { it.print() }).nonlinearity().evolve(rule, steps - 1,hashes + hashCode)

fun FreeMatrix<Context<Boolean?>?>.str() = transpose.map { if(it?.q == true) "1" else " " }.toString()
fun FreeMatrix<Context<Boolean?>?>.print() = println(str())

fun Context<Boolean?>.applyRule(
  // https://www.wolframalpha.com/input?i=rule+110
  rule: (Boolean, Boolean, Boolean) -> Boolean = { p, q, r -> (q && !p) || (q xor r) }
): Context<Boolean?> = Context(null, rule(p ?: false, q!!, r ?: false), null)

fun FreeMatrix<Context<Boolean?>?>.nonlinearity() =
  FreeMatrix(numRows, 1) { r, c -> this[r, c]?.applyRule() }

fun algebra() =
  Ring.of<Context<Boolean?>?>(
    nil = null,
    times = { a: Context<Boolean?>?, b: Context<Boolean?>? ->
      if (a == null && b == null) null
      else if (a != null && b != null) Context(null, b.π2, null)
      else null
    },
    plus = { a: Context<Boolean?>?, b: Context<Boolean?>? ->
      if (a == null && b != null) Context(b.π2, null, null)
      else if (a != null && b != null)
        if (a.π2 == null) Context(a.π1, b.π2, null)
        else Context(a.π1, a.π2, b.π2)
      else if (a != null && b == null) a
      else null
    }
  )

// Rule 110 Encoding
fun r(p: T, q: T, r: T) = F
fun r(p: T, q: T, r: F) = T
fun r(p: T, q: F, r: T) = T
fun r(p: T, q: F, r: F) = F
fun r(p: F, q: T, r: T) = T
fun r(p: F, q: T, r: F) = T
fun r(p: F, q: F, r: T) = T
fun r(p: F, q: F, r: F) = F

// Typelevel implementation of Rule 110
val eca10 = BVec(F, F, F, F, F, F, F, F, F, T)
  .eca(::r, ::r, ::r, ::r, ::r, ::r, ::r, ::r, ::r, ::r)
  .eca(::r, ::r, ::r, ::r, ::r, ::r, ::r, ::r, ::r, ::r)
  .eca(::r, ::r, ::r, ::r, ::r, ::r, ::r, ::r, ::r, ::r)
  .eca(::r, ::r, ::r, ::r, ::r, ::r, ::r, ::r, ::r, ::r)
  .eca(::r, ::r, ::r, ::r, ::r, ::r, ::r, ::r, ::r, ::r)
  .eca(::r, ::r, ::r, ::r, ::r, ::r, ::r, ::r, ::r, ::r)
  .eca(::r, ::r, ::r, ::r, ::r, ::r, ::r, ::r, ::r, ::r)
  .eca(::r, ::r, ::r, ::r, ::r, ::r, ::r, ::r, ::r, ::r)
  .eca(::r, ::r, ::r, ::r, ::r, ::r, ::r, ::r, ::r, ::r)
  .eca(::r, ::r, ::r, ::r, ::r, ::r, ::r, ::r, ::r, ::r)
  .eca(::r, ::r, ::r, ::r, ::r, ::r, ::r, ::r, ::r, ::r)
  .eca(::r, ::r, ::r, ::r, ::r, ::r, ::r, ::r, ::r, ::r)
  .eca(::r, ::r, ::r, ::r, ::r, ::r, ::r, ::r, ::r, ::r)
  .eca(::r, ::r, ::r, ::r, ::r, ::r, ::r, ::r, ::r, ::r)
  .eca(::r, ::r, ::r, ::r, ::r, ::r, ::r, ::r, ::r, ::r)
  .eca(::r, ::r, ::r, ::r, ::r, ::r, ::r, ::r, ::r, ::r)
  .eca(::r, ::r, ::r, ::r, ::r, ::r, ::r, ::r, ::r, ::r)
  .eca(::r, ::r, ::r, ::r, ::r, ::r, ::r, ::r, ::r, ::r)
  .eca(::r, ::r, ::r, ::r, ::r, ::r, ::r, ::r, ::r, ::r)
  .eca(::r, ::r, ::r, ::r, ::r, ::r, ::r, ::r, ::r, ::r)
  .eca(::r, ::r, ::r, ::r, ::r, ::r, ::r, ::r, ::r, ::r)
  .eca(::r, ::r, ::r, ::r, ::r, ::r, ::r, ::r, ::r, ::r)
  .eca(::r, ::r, ::r, ::r, ::r, ::r, ::r, ::r, ::r, ::r)
  .eca(::r, ::r, ::r, ::r, ::r, ::r, ::r, ::r, ::r, ::r)
  .eca(::r, ::r, ::r, ::r, ::r, ::r, ::r, ::r, ::r, ::r)
  .eca(::r, ::r, ::r, ::r, ::r, ::r, ::r, ::r, ::r, ::r)
  .eca(::r, ::r, ::r, ::r, ::r, ::r, ::r, ::r, ::r, ::r)

fun <
  B0: Bool<B0, *, *, *, *, *>,
  B1: Bool<B1, *, *, *, *, *>,
  B2: Bool<B2, *, *, *, *, *>,
  B3: Bool<B3, *, *, *, *, *>,
  B4: Bool<B4, *, *, *, *, *>,
  B5: Bool<B5, *, *, *, *, *>,
  B6: Bool<B6, *, *, *, *, *>,
  B7: Bool<B7, *, *, *, *, *>,
  B8: Bool<B8, *, *, *, *, *>,
  B9: Bool<B9, *, *, *, *, *>,
  Y0, Y1, Y2, Y3, Y4, Y5, Y6, Y7, Y8, Y9,
> BVec10<B0, B1, B2, B3, B4, B5, B6, B7, B8, B9>.eca(
  op0: (B9, B0, B1) -> Y0,
  op1: (B0, B1, B2) -> Y1,
  op2: (B1, B2, B3) -> Y2,
  op3: (B2, B3, B4) -> Y3,
  op4: (B3, B4, B5) -> Y4,
  op5: (B4, B5, B6) -> Y5,
  op6: (B5, B6, B7) -> Y6,
  op7: (B6, B7, B8) -> Y7,
  op8: (B7, B8, B9) -> Y8,
  op9: (B8, B9, B0) -> Y9,
) =
  BVec10(
    op0(b9, b0, b1),
    op1(b0, b1, b2),
    op2(b1, b2, b3),
    op3(b2, b3, b4),
    op4(b3, b4, b5),
    op5(b4, b5, b6),
    op6(b5, b6, b7),
    op7(b6, b7, b8),
    op8(b7, b8, b9),
    op9(b8, b9, b0),
  )