package ai.hypergraph.reasoning

import ai.hypergraph.kaliningraph.joinToScalar
import ai.hypergraph.kaliningraph.tensor.FreeMatrix
import ai.hypergraph.kaliningraph.tensor.Matrix
import ai.hypergraph.kaliningraph.tensor.UTMatrix
import ai.hypergraph.kaliningraph.types.cache
import ai.hypergraph.kaliningraph.types.*
import org.kosat.Kosat
import kotlin.jvm.JvmName
import kotlin.math.absoluteValue
import kotlin.random.Random

typealias CNF = Set<Clause>
typealias Clause = Set<Literal>
typealias Literal = Int
typealias SATVector = Array<CNF>
typealias SATRubix = UTMatrix<CNF>

fun CNF.pretty() = joinToString("\n") { it.joinToString(",") { it.toString() } }

val CNF.asInt by cache { flatten().first() }
val CNF.variables by cache { flatten().map { it.absoluteValue }.toSet() }
val CNF.hashToIdx by cache { variables.mapIndexed { idx, hash -> hash to idx + 1 }.toMap() }
val CNF.solver by cache {
  val t =
    map { it.map { hashToIdx[it.absoluteValue]!! * if (it < 0) -1 else 1 }.toMutableList() }.toMutableList()
  Kosat(t, variables.size)
}

val CNF.solution: Model by cache {
  solver
//    .run { if(!solve()) null else this } // TODO: Needs a way to represent UNSAT
    .getModel().toSet()
    .let { s -> Model(variables.associateWith { hashToIdx[it] in s }) }
}

class Model(val varMap: Map<Int, Boolean>): Map<Int, Boolean> by varMap {
  operator fun get(cnf: CNF): Boolean? = varMap[cnf.flatten().first()]
  override fun get(key: Int): Boolean? = varMap[key]
  override fun toString() = varMap.toString()
}

fun Boolean.toCNF(): CNF = if (this) T else F
fun Int.toCNF(): CNF = setOf(setOf(absoluteValue))
fun Int.asCNF(): CNF = setOf(setOf(this))

object T: CNF by setOf(setOf())
object F: CNF by setOf(setOf())

infix fun T.v(t: T) = T
infix fun T.v(t: F) = T
infix fun F.v(t: T) = T
infix fun F.v(t: F) = F

infix fun T.ʌ(t: T) = T
infix fun T.ʌ(t: F) = F
infix fun F.ʌ(t: T) = F
infix fun F.ʌ(t: F) = F

@JvmName("fob") infix fun CNF.v(t: F) = this
@JvmName("lob") infix fun Literal.v(t: F) = this
@JvmName("fot") infix fun CNF.v(t: T) = T
@JvmName("lot") infix fun Literal.v(t: T) = T
@JvmName("tof") infix fun T.v(t: CNF) = T
@JvmName("tol") infix fun T.v(t: Literal) = T
@JvmName("bof") infix fun F.v(c: CNF) = c
@JvmName("bol") infix fun F.v(l: Literal) = l

@JvmName("fab") infix fun CNF.ʌ(t: F) = F
@JvmName("lab") infix fun Literal.ʌ(t: F) = F
@JvmName("fat") infix fun CNF.ʌ(t: T) = this
@JvmName("lat") infix fun Literal.ʌ(t: T) = this
@JvmName("taf") infix fun T.ʌ(t: CNF) = t
@JvmName("tal") infix fun T.ʌ(t: Literal) = t
@JvmName("baf") infix fun F.ʌ(t: CNF) = F
@JvmName("bal") infix fun F.ʌ(t: Literal) = F

@JvmName("lal") infix fun Literal.ʌ(l: Literal): CNF = setOf(setOf(this), setOf(l))
@JvmName("fal") infix fun CNF.ʌ(l: Literal): CNF = plus(setOf(setOf(l)))
@JvmName("laf") infix fun Literal.ʌ(c: CNF): CNF = c.plus(setOf(setOf(this)))
@JvmName("faf") infix fun CNF.ʌ(c: CNF): CNF = plus(c)

// Nothing very interesting happens up until this point.
// Follows Jason's "Building up CNF CNFs Directly" strategy:
// https://www.cs.jhu.edu/~jason/tutorials/convert-to-CNF.html
@JvmName("lol") infix fun Literal.v(l: Literal): CNF = setOf(setOf(this, l))
@JvmName("fol") infix fun CNF.v(l: Literal): CNF = this v l.asCNF()
@JvmName("lof") infix fun Literal.v(l: CNF): CNF = l v asCNF()
@JvmName("fof") infix fun CNF.v(that: CNF): CNF = when {
  this is T -> T
  this is F -> that
  that.size == 1 && that.first().size == 1 -> map { it + that.first() }.toSet()
  this.size == 1 && this.first().size == 1 -> that.map { it + first() }.toSet()
  else -> FreshLit().let { (-it v this) ʌ (it v that) }
}

fun FreshLit(s: Set<Int> = emptySet()): Literal =
  generateSequence { Random.nextInt().absoluteValue }.dropWhile { it in s }.first()

// TODO: Not sure how quickly this will blow up, but let's see...
@JvmName("fef") infix fun CNF.eq(c: CNF): CNF = (this ʌ c) v (negate() ʌ c.negate())
@JvmName("fxf") infix fun CNF.ⴲ(c: CNF): CNF = (this ʌ c.negate()) v (c.negate() ʌ this)
@JvmName("lng") fun Literal.negate(): Literal = -this
@JvmName("fng") fun CNF.negate(): CNF =
  map { it.map { setOf(-it) }.toSet() }.flatten().toSet()

val RXOR_SAT_ALGEBRA get() =
  Ring.of(
    nil = F,
    one = T,
    plus = { a, b -> a ⴲ b },
    times = { a, b -> a ʌ b }
  )

val RSAT_ALGEBRA get() =
  Ring.of(
    nil = F,
    one = T,
    plus = { a, b -> a v b },
    times = { a, b -> a ʌ b }
  )

fun BVar(name: String): CNF = name.hashCode().toCNF()
fun BVecVar(size: Int, prefix: String = "", pfx: (Int) -> String = { prefix }): SATVector =
  Array(size) { k -> BVar("${pfx(k)}_f::$k") }
fun RMatVar(name: String, algebra: Ring<CNF>, rows: Int, cols: Int = rows) =
  FreeMatrix(algebra, rows, cols) { i, j -> BVar("$name$i$j") }
fun BLit(b: Boolean): CNF = b.toCNF()
fun BVecLit(l: BooleanArray): SATVector = l.map { it.toCNF() }.toTypedArray()
fun BVecLit(l: List<Boolean>): SATVector = BVecLit(l.toBooleanArray())
fun BVecLit(size: Int, f: (Int) -> CNF): SATVector = Array(size) { f(it) }

infix fun SATVector.eq(that: SATVector): CNF =
  if (size != that.size) throw Exception("Shape mismatch, incomparable!")
  else zip(that).map { (a, b) -> a eq b }.reduce { a, b -> a ʌ b }

// Only compare upper triangular entries of the matrix
infix fun Matrix<CNF, *, *>.eqUT(that: Matrix<CNF, *, *>): CNF =
  joinToScalar(this, that, filter = { r, c -> r < c }, join = { a, b -> a eq b }, reduce = { a, b -> a ʌ b })

infix fun Matrix<CNF, *, *>.eq(that: Matrix<CNF, *, *>): CNF =
  if (shape() != that.shape()) throw Exception("Shape mismatch, incomparable!")
  else joinToScalar(this, that, join = { a, b -> a eq b }, reduce = { a, b -> a ʌ b })

infix fun Matrix<CNF, *, *>.neq(that: Matrix<CNF, *, *>): CNF = (this eq that).negate()
