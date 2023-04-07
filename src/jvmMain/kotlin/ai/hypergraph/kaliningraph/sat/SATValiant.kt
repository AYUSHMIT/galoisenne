package ai.hypergraph.kaliningraph.sat

import ai.hypergraph.kaliningraph.parsing.*
import ai.hypergraph.kaliningraph.sampling.pow
import ai.hypergraph.kaliningraph.tensor.*
import ai.hypergraph.kaliningraph.types.*
import org.logicng.formulas.Formula
import kotlin.collections.filter

typealias SATVector = Array<Formula>
typealias SATRubix = UTMatrix<SATVector>

val SATRubix.stringVariables by cache { diagonals.first() }

@JvmName("joinFormula")
fun CFG.join(left: SATVector, right: SATVector): SATVector =
  if (left.isEmpty() || right.isEmpty()) arrayOf()
  else Array(left.size) { i ->
    bimap[bindex[i]].filter { 1 < it.size }.map { it[0] to it[1] }
      .map { (B, C) -> left[bindex[B]] and right[bindex[C]] }
      .fold(F) { acc, satf -> acc or satf }
//      .map { (B, C) -> left[bindex[B]].let{ it.factory().and(it, right[bindex[C]])} }
//      .fold(left.first().factory().falsum() as Formula) { acc, satf -> acc.factory().or(acc, satf) }
  }

@JvmName("satFormulaUnion")
infix fun SATVector.union(that: SATVector): SATVector =
  if (isEmpty()) that else if (that.isEmpty()) this
  else Array(size) { i -> this[i] or that[i] }
//  if (isEmpty()) that else if (that.isEmpty()) this
//  else Array(size) { i -> this[i].let { it.factory().or(it, that[i]) } }

fun BooleanArray.toLitVec(): SATVector = map { BLit(it) }.toTypedArray()

fun vecsEqAtIndices(a: SATVector, b: SATVector, indices: Set<Int>): Formula =
  if (a.isEmpty() || b.isEmpty() || a.size != b.size) throw Exception("Shape mismatch! (${a.size}, ${b.size})")
  else indices.map { i -> a[i] eq b[i] }.reduce { acc, satf -> acc and satf }

infix fun SATVector.vecEq(that: SATVector): Formula =
  if (isEmpty() || that.isEmpty() || size != that.size) throw Exception("Shape mismatch! ($size, ${that.size})")
  else if (contentEquals(that)) T
  else zip(that).partition { (l, r) -> l == r }
    .second.map { (a, b) -> a eq b }
    .let { if (it.isEmpty()) T else it.reduce { acc, satf -> acc and satf } }

fun SATRubix.valiantMatEq(cfg: CFG, that: SATRubix): Formula =
  if (shape() != that.shape()) throw Exception("Shape mismatch! (${shape()}, ${that.shape()})")
  else {
    val reachSeq = cfg.graph
      .let { it.reachSequence(it.vertices.filter { it.label in cfg.startSymbols }.toSet()) }
      .map { it.map { it.label }.filter { it in cfg.nonterminals }.map { cfg.bindex[it] }.toSet() }.iterator()

    diagonals.drop(1).dropLast(1).flatten().zip(that.diagonals.drop(1).dropLast(1).flatten()).reversed()
      .map { (a, b) -> vecsEqAtIndices(a, b, reachSeq.next()) }.reduce { acc, satf -> acc and satf } and
        cfg.startSymbols.map {
          diagonals.last().first()[cfg.bindex[it]] eq that.diagonals.last().first()[cfg.bindex[it]]
        }.reduce { acc, satf -> acc and satf }
  }
// TODO: Only compare nonterminals that are reachable in 1-step from the start symbol at the second-to-last level,
//       and the nonterminals that are reachable in 2-steps from the start symbol at the third-to-last level, etc.

// Encodes the constraint that bit-vectors representing a unary production
// should not contain mixed NT symbols, e.g., given A->(, B->(, C->), D->)
// the bitvector cannot have the configuration [A=1 B=1 C=0 D=1], it must
// be either [A=1 B=1 C=0 D=0] or [A=0 B=0 C=1 D=1].
fun CFG.mustBeOnlyOneTerminal(bitvec: SATVector): Formula {
  val ntbv = bitvec.projectOnto(nonterminals)
  // terminal                 possible nonterminals it can represent
  return (terminals - blocked).map { bitvec.projectOnto(bimap[listOf(it)], nonterminals) }.map { possibleNTs ->
    val (insiders, outsiders) = ntbv.partition { it in possibleNTs }
    (insiders + outsiders.map { it.negate() }).reduce { acc, satf -> acc and satf }
  }.reduce { acc, satf -> acc xor satf }
}

// Returns list elements matching the intersection between set and on (indexed by on)
fun <E, T> Array<E>.projectOnto(set: Set<T>, on: Set<T> = set): Set<E> =
  if (size != on.size) throw Exception("Size mismatch: List[$size] != Set[${on.size}]")
  else set.intersect(on).map { this[on.indexOf(it)] }.toSet()

// Encodes that each blank can only be a single terminal
fun CFG.uniquenessConstraints(rubix: SATRubix, tokens: List<Σᐩ>): Formula =
  rubix.stringVariables.zip(tokens)
    .filter { it.second.isHoleTokenIn(this) }
    .map { mustBeOnlyOneTerminal(it.first) }
    .fold(T) { acc, it -> acc and it }

// Encodes that nonterminal stubs can only be replaced by reachable nonterminals
fun CFG.reachabilityConstraints(tokens: List<Σᐩ>, rubix: SATRubix): Formula =
  tokens.zip(rubix.stringVariables)
    .filter { (word, _) -> word.isNonterminalStubIn(cfg = this) }
    .map { (nonterminalStub, hf) ->
      val nt = nonterminalStub.drop(1).dropLast(1)
      nonparametricForm.equivalenceClass(nt)
        .map { hf eq BVecLit(toBitVec(setOf(it))) }
        .fold(F) { a, b -> a xor b }
    }.flatten().fold(T) { a, b -> a and b }

// Computes equivalences between unit nonterminals in each CFG
fun CJL.alignNonterminals(rubices: List<SATRubix>): Formula {
  if (rubices.size == 1) return T

  // For each terminal shared by every CFG, compute the nonterminal sets each one is generated by
  // i.e., computes the groups of nonterminals that are equivalent.
  // TODO: maybe simplify using a more brittle equivalence relation, e.g. NT name equality
  val terminalsToNTs =
    cfgs.map { it.terminals }.intersect().map { terminal ->
//      println("====$terminal====")
      cfgs.map { cfg ->
        val nts = cfg.bimap[listOf(terminal)]
//      println("NT: ${nts.joinToString(",", "[", "]") { "($it -> $terminal)" }}")

        /* Heuristic to select the smallest terminal preimage, e.g., given two CFGs
         * [[A -> a | d], D -> a] ⊆ CFG1
         * [[M -> a | p], Q -> a] ⊆ CFG2
         * we want to select D and Q as the preimage of a, instead of A and M.
        */
        val firstBijectiveNT = nts.minByOrNull { cfg.bimap[it].size }!!
//      println("First bijective NT: $firstBijectiveNT")
        cfg.bindex[firstBijectiveNT]
      }
//        .also { println("=========\n") }
    }

  if (terminalsToNTs.isEmpty()) return F.also { println("No terminals in common!") }

  // For each group of equivalent nonterminals, bind them together using == constraints, e.g.
  // [[A, B, C], [E, F, G]] -> [A == B] ʌ [B == C] ʌ [E == F] ʌ [F == G]
  // except we use indices to track the nonterminals positions in each rubix
  return rubices.map { it.stringVariables }
    .let { FreeMatrix(rubices.size, it.first().size, it.flatten()) }.cols
    .map { vecs ->
      terminalsToNTs.map {
        it.windowed(2).map { it[0] to it[1] }
          .zip(vecs.windowed(2).map { it[0] to it[1] })
          .map { (a, b) ->
            val (i1, i2) = a
            val (v1, v2) = b
            v1[i1] eq v2[i2]
          }
      }
    }.flatten().flatten().fold(T) { a, b -> a and b }
}

val CFG.satAlgebra by cache {
  Ring.of(
    nil = arrayOf(),
    one = Array(nonterminals.size) { T },
    plus = { a, b -> a union b },
    times = { a, b -> join(a, b) }
  )
}

fun CFG.encodeTokenAsSATVec(token: Σᐩ): SATVector =
  bimap[listOf(token)].let { nts -> nonterminals.map { it in nts } }
    .toBooleanArray().toLitVec()

fun CFG.encodeTokens(rubix: SATRubix, strings: List<Σᐩ>): Formula =
  if (strings.size == 1) {
    // Precomputes literals (permanent upper right triangular submatrices) in
    // the fixpoint to avoid solving for invariant entries that are fixed.
    val literalUDM: UTMatrix<BooleanArray?> = UTMatrix(
      ts = strings.map { it ->
        // Nulls on the superdiagonal will cast either a rectangular or pentagonal
        // shadow of bitvector variables on UTMatrix, which we represent as nulls
        if (it.isHoleTokenIn(cfg = this)) null
        // Terminals will cast a triangular shadow of bitvector literals on UTMatrix
        else bimap[listOf(it)].let { nts -> nonterminals.map { it in nts } }.toBooleanArray()
      }.toTypedArray(),
      algebra = satLitAlgebra
    ).seekFixpoint()
//    println(rubix.toFullMatrix().summarize(this))
//    println(FreeMatrix(literalUDM.data.map { it?.toLitVec() ?: arrayOf() }).summarize(this))
    rubix.data.zip(literalUDM.data).fold(T) { acc, (a, b) ->
      if (b == null || b.isEmpty() || a.isEmpty()) acc else acc and (a.vecEq(b.toLitVec()))
    }
  } else
    rubix.stringVariables.zip(strings).fold(T) { acc: Formula, (v, b) ->
      acc and v.vecEq(if (b.isHoleTokenIn(cfg = this)) v else encodeTokenAsSATVec(b))
    }

// Since Valiant matrix multiplication procedure takes a long time, and we use
// the same base matrix each time, we precompute a large matrix template and
// reuse submatrices for each individual sketch.
//val CFG.parseMatrix: Π2A<SATRubix> by cache {
//  constructRubix(MAX_TOKENS).let { it to (it * it) }.let { (a, b) ->
//    a to (a matEq b)
//  }
//}

//fun CFG.matrix(dim: Int): SATRubix = parseMatrix.first.submatrix(dim, this)
//fun CFG.matrixFPEq(dim: Int): SATRubix = parseMatrix.second.submatrix(dim, this)

//fun SATRubix.submatrix(dim: Int): SATRubix =
//   UTMatrix((0 until dim).mapIndexed { i, d -> diagonals[i].subList(0, dim - i) }, algebra)

//fun SATRubix.submatrix(dim: Int, cfg: CFG): SATRubix =
//  UTMatrix((0 until dim).mapIndexed { i, d -> diagonals[i].subList(0, dim - i) }
//    .map { it.map { it.map { ff.importFormula(it) }.toTypedArray() } }, cfg.satAlgebra)

//fun UTMatrix<Array<String>?>.submatrix(dim: Int, cfg: CFG): SATRubix =
//  UTMatrix((0 until dim).mapIndexed { i, d -> diagonals[i].subList(0, dim - i) }
//    .map { it.map { it!!.map { ff.parse(it) }.toTypedArray() } }, cfg.satAlgebra)


//infix fun SATRubix.matEq(other: SATRubix) =
//  UTMatrix(diagonals.zip(other.diagonals).map { (c, d) ->
//    c.zip(d).map { (e, f) -> e.zip(f).map { (g, h) -> g eq h }.toTypedArray() } }, algebra)

//@OptIn(ExperimentalTime::class)
//fun CFG.isInGrammar(i: Int): Pair<Formula, SATRubix> =
//  measureTimedValue {
//  constructRubix(i).let { it to (it matEq it * it) }
////    (matrix(i) to matrixFPEq(i))
//    .let { (s, t ) ->
//      startSymbols.fold(F) { acc, it -> acc or s.diagonals.last().first()[bindex[it]] } and
//      t.data.map { it.toList() }.flatten().fold(T) { a, b -> a and b } to s
//    }
//  }.also { println("Formed grammar constraints in ${it.duration.inWholeMilliseconds}ms") }.value
//
//fun CFG.generateConstraints(tokens: List<Σᐩ>): Pair<Formula, SATRubix> {
//  val (t, q) = isInGrammar(tokens.size)
//  return t and
//    encodeTokens(q, tokens) and
//    uniquenessConstraints(q, tokens) and
//    reachabilityConstraints(tokens, q) to q
//}

//@OptIn(ExperimentalTime::class)
fun CFG.isInGrammar(mat: SATRubix): Formula =
  startSymbols.fold(F) { acc, it -> acc or mat.diagonals.last().first()[bindex[it]] } and
    // TODO: Cache this to speedup computation?
    (mat.valiantMatEq(this, mat * mat))//measureTimedValue{ mat * mat }.also { println("Matmul took: ${it.duration}") }.value)

fun CFG.constructRubix(numTokens: Int): SATRubix =
  FreeMatrix(satAlgebra, numTokens + 1) { r, c ->
    // Strictly upper triangular matrix entries
    if (r + 1 <= c) BVecVar(nonterminals.size) { i -> "HV_r::${r}_c::${c}_cfgHash::${hashCode()}" }
    // Diagonal and subdiagonal
    else arrayOf()
  }.toUTMatrix()

fun CFG.generateConstraints(
  tokens: List<Σᐩ>,
  rubix: SATRubix = constructRubix(tokens.size)
): Pair<Formula, SATRubix> =
  isInGrammar(rubix)/*.also { print("FormulaSize={isInGrammar: ${it.numberOfNodes()},")}*/ and
    encodeTokens(rubix, tokens)/*.also { print("encodeTokens: ${it.numberOfNodes()},")}*/ and
    uniquenessConstraints(rubix, tokens)/*.also { print("uniquenessConstraints: ${it.numberOfNodes()},")}*/ and
    reachabilityConstraints(tokens, rubix)/*.also { println("reachabilityConstraints: ${it.numberOfNodes()}}")}*/ to rubix

// TODO: incrementalize
fun CJL.generateConstraints(tokens: List<Σᐩ>): Pair<Formula, SATRubix> {
  ff.clear()
  println("Synthesizing (${tokens.size}): ${tokens.joinToString(" ")}")
  val timeToFormConstraints = System.currentTimeMillis()
  val (t, q) = cfgs.map { it.generateConstraints(tokens) }.unzip()
  val parsingConstraints = t.fold(T) { a, b -> a and b } and alignNonterminals(q)

  val timeElapsed = System.currentTimeMillis() - timeToFormConstraints
  println("Solver formed ${parsingConstraints.numberOfNodes()} constraints in ${timeElapsed}ms")

  return parsingConstraints to q.first()
}

/** Currently just a JVM wrapper around the multiplatform [synthesizeWithVariations] */
fun Σᐩ.synthesizeIncrementally(
  cfg: CFG,
  allowNTs: Boolean = true,
  enablePruning: Boolean = false,
  variations: List<Mutator> = listOf({ _, _ -> sequenceOf() }),
  updateProgress: (Σᐩ) -> Unit = {},
  takeMoreWhile: () -> Boolean = { !Thread.currentThread().isInterrupted },
  synthesizer: CFG.(List<Σᐩ>) -> Sequence<Σᐩ> = {
    if (it.isSetValiantOptimalFor(this))
      it.also { println("Synthesizing with SetValiant: ${it.joinToString(" ")}") }
      .solve(this, takeMoreWhile = takeMoreWhile)
    else asCJL.synthesize(it, takeMoreWhile = takeMoreWhile)
  }
): Sequence<Σᐩ> = synthesizeWithVariations(
  cfg = cfg,
  allowNTs = allowNTs,
  variations = variations,
  enablePruning = enablePruning,
  updateProgress = updateProgress,
  synthesizer = synthesizer
)

/*
Does Lee's method give demonstrable speedup? https://arxiv.org/pdf/cs/0112018.pdf#page=10
It seems Valiant gives a reduction from CFL parsing to BMM, i.e., CFL→BMM and
Lee shows that a faster procedure for BMM would automatically give a fast
procedure for CFL parsing, i.e., BMM⇄CFL.

Lowers Valiant matrix onto SAT. Steps:
  1.) Encode CFL as BMM.
  2.) Symbolically evaluate BMM to get a Boolean formula.
  3.) Encode symbolic Boolean formula as CNF using Tseitin.
  4.) Run SAT solver and decode variable assignments.

  https://people.csail.mit.edu/virgi/6.s078/papers/valiant.pdf#page=13
  https://www.ps.uni-saarland.de/courses/seminar-ws06/papers/07_franziska_ebert.pdf#page=6
*/

fun CFG.synthesize(tokens: List<Σᐩ>): Sequence<Σᐩ> =
  asCJL
//    .also { println("Solving (Complexity: ${(terminals - blocked).size.pow(tokens.count { it == "_" })}): ${tokens.joinToString(" ")}") }
    .synthesize(tokens)

// TODO: As new keystrokes are received, we should incrementally update
//  existing constraints rather than creating a fresh SAT instance.
/** [generateConstraints] */
fun CJL.synthesize(
  tokens: List<Σᐩ>,
  takeMoreWhile: () -> Boolean = { !Thread.currentThread().isInterrupted }
): Sequence<Σᐩ> {
  check(tokens.all { it in symbols || it == HOLE_MARKER || it.isNonterminalStub() })
  { "All tokens passed into synthesize() must be contained in all CFGs" }
  return when {
    tokens.none { it.isHoleTokenIn(cfg = cfgs.first()) } -> emptySequence<Σᐩ>().also { println("No holes!") }
    tokens.size == 1 -> cfgs.map { it.handleSingleton(tokens.first()) }.intersect().asSequence()
    else -> sequence {
      val (parsingConstraints, rubix) = generateConstraints(tokens)
      val strVars = rubix.stringVariables.fold(setOf<Formula>()) { a, b -> a + b }

      // FormulaDimacsFileWriter.write("dimacs.cnf", parsingConstraints.cnf(), true)
      // https://www.utbot.org/kosat/
      // Sometimes simplification can take longer or even switch SAT->UNSAT?
      // println("Original: ${parsingConstraints.numberOfNodes()}")
      // parsingConstraints = AdvancedSimplifier().apply(parsingConstraints, false)
      // parsingConstraints = BackboneSimplifier.get().apply(parsingConstraints, false)
      // println("Reduction: ${parsingConstraints.numberOfNodes()}")
      // println(parsingConstraints.cnf().toPython())

      var (solver, model) = parsingConstraints.solveIncrementally(takeMoreWhile = takeMoreWhile)
      // LogicNG's Formula datatype is not monoidal/threadsafe, so we cannot run it in parallel.
      // Instead we want an immutable Formula datatype that can be combined without affecting solver.
      // This would enable incremental editing, rollbacks, reset to initial state, etc.
      // TODO: var (solver, model) = parsingConstraints.solveUsingKosat()
      model.ifEmpty { ff.clear(); return@sequence }

      //  var totalFreshnessConstraints = 0L
      // Tries to enumerate all strings that satisfy the constraints, adding a freshness constraint after each one.
      while (true) try {
        // In the case of intersections, which CFG is used to generate the string does not matter.
        val cfg = cfgs.first()
        // Decode model from SAT solver into the corresponding string
        val fillers = rubix.stringVariables.zip(tokens)
          .map { (bits, token) ->
            // If the token is not a hole token, use the original token.
            if (cfgs.none { token.isHoleTokenIn(it) }) token
            // Otherwise, use the model to decode the bits into a terminal.
            else cfg.tmap[cfg.nonterminals(bits.map { model[it]!! })]
          }

        val completion: Σᐩ = fillers.joinToString(" ")

        if (completion.trim().isNotBlank()) yield(completion)

        val isFresh = model.filter { (k, v) -> k in strVars && v }.areFresh()
        // freshnessConstraints += isFresh.numberOfAtoms()
        // println("Total freshness constraints: $totalFreshnessConstraints")

        model = solver.addConstraintAndSolve(isFresh, takeMoreWhile)
        // If model is empty or we receive an error, assume that all models have been exhausted.
        .ifEmpty { ff.clear(); return@sequence }
      } catch (ie: InterruptedException) {
        ff.clear()
        throw ie
      } catch (npe: NullPointerException) {
        System.err.println("NPE when solving: ${tokens.joinToString(" ")}")
        npe.printStackTrace()
        ff.clear()
        return@sequence
      } catch (oom: OutOfMemoryError) { // Does this really work?
        System.err.println("OOM when solving: ${tokens.joinToString(" ")}")
        oom.printStackTrace()
        ff.clear()
        return@sequence
      }
    }
  }
}
