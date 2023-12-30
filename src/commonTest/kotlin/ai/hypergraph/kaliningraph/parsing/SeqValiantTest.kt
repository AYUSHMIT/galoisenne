package ai.hypergraph.kaliningraph.parsing

import Grammars.arith
import Grammars.evalArith
import Grammars.toyArith
import ai.hypergraph.kaliningraph.*
import org.kosat.round
import kotlin.random.Random
import kotlin.test.*
import kotlin.time.*

/*
./gradlew jvmTest --tests "ai.hypergraph.kaliningraph.parsing.SeqValiantTest"
*/
class SeqValiantTest {
/*
./gradlew jvmTest --tests "ai.hypergraph.kaliningraph.parsing.SeqValiantTest.testSeqValiant"
*/
  @Test
  fun testSeqValiant() {
    var clock = TimeSource.Monotonic.markNow()
    val detSols = Grammars.seq2parsePythonCFG.noEpsilonOrNonterminalStubs
      .enumSeq(List(20) {"_"})
      .take(10_000).sortedBy { it.length }.toList()

    detSols.forEach { assertTrue("\"$it\" was invalid!") { it in Grammars.seq2parsePythonCFG.language } }

    var elapsed = clock.elapsedNow().inWholeMilliseconds
    println("Found ${detSols.size} determinstic solutions in ${elapsed}ms or ~${detSols.size / (elapsed/1000.0)}/s, all were valid!")

    clock = TimeSource.Monotonic.markNow()
    val randSols = Grammars.seq2parsePythonCFG.noEpsilonOrNonterminalStubs
      .sampleSeq(List(20) { "_" }).take(10_000).toList().distinct()
      .onEach { assertTrue("\"$it\" was invalid!") { it in Grammars.seq2parsePythonCFG.language } }

    // 10k in ~22094ms
    elapsed = clock.elapsedNow().inWholeMilliseconds
    println("Found ${randSols.size} random solutions in ${elapsed}ms or ~${randSols.size / (elapsed/1000.0)}/s, all were valid!")
  }

/*
./gradlew jvmTest --tests "ai.hypergraph.kaliningraph.parsing.SeqValiantTest.testCompareSolvers"
*/
  @Test
  fun testCompareSolvers() {
    val prompt = "_ _ ( _ _ _".tokenizeByWhitespace()
    val enumSeq = measureTimedValue { toyArith.enumSeq(prompt).toSet() }
    val solveSeq = measureTimedValue { toyArith.solveSeq(prompt).toSet() }
    val origSet = measureTimedValue { prompt.solve(toyArith).toSet() }

//  EnumSeq: 584 (842.693834ms)
//  SolvSeq: 584 (3.802375ms)
//  SetCYK: 584 (7.388834667s)
    enumSeq.also { println("EnumSeq: ${it.value.size} (${it.duration})") }.value
    solveSeq.also { println("SolvSeq: ${it.value.size} (${it.duration})") }.value
    origSet.also { println("SetCYK: ${it.value.size} (${it.duration})") }.value

    assertEquals(origSet.value, enumSeq.value, "EnumSeq was missing:" + (origSet.value - enumSeq.value))
    assertEquals(origSet.value, solveSeq.value)
  }

/*
./gradlew jvmTest --tests "ai.hypergraph.kaliningraph.parsing.SeqValiantTest.testBalancedBrackets"
*/
  @Test
  fun testBalancedBrackets()  {
    val cfg = "S -> [ S ] | [ ] | S S".parseCFG().noNonterminalStubs

    println(cfg.prettyPrint())

    cfg.enumSeq("_ _ _ _ _ _".tokenizeByWhitespace())
      .filter { (it.matches(cfg) to it.hasBalancedBrackets())
        .also { (valiant, stack) ->
          // Should never see either of these statements if we did our job correctly
          if (!valiant && stack) println("SeqValiant under-approximated Stack: $it")
          else if (valiant && !stack) println("SeqValiant over-approximated Stack: $it")
          assertFalse(!valiant && stack || valiant && !stack)
        }.first
      }.take(100).toList()
      .also { assertTrue(it.isNotEmpty()) }
      .forEach { decodedString ->
        println("$decodedString generated by SeqValiant!")

        val isValid = decodedString.matches(cfg)
        println("$decodedString is ${if (isValid) "" else "not "}valid according to SetValiant!")

        assertTrue(isValid)
      }
  }

/*
./gradlew jvmTest --tests "ai.hypergraph.kaliningraph.parsing.SeqValiantTest.testCheckedArithmetic"
*/
  @Test
  fun testCheckedArithmetic() {
    Grammars.checkedArithCFG.enumSeq("( _ + _ ) * ( _ + _ ) = ( _ * _ ) + ( _ * _ )".tokenizeByWhitespace())
      .take(10).toList().also { assertTrue(it.isNotEmpty()) }
      .map {
        println(it)
        val (left, right) = it.split('=')
        val (ltree, rtree) = arith.parse(left)!! to arith.parse(right)!!
        val (leval, reval) = ltree.evalArith() to rtree.evalArith()
        println("$leval = $reval")
        assertEquals(leval, reval)
        leval
      }.distinct().take(4).toList()
  }

  /*
./gradlew jvmTest --tests "ai.hypergraph.kaliningraph.parsing.SeqValiantTest.testRandomCFG"
*/
  @Test
  fun testRandomCFG() {
    fun String.deleteRandomSingleWord() =
      tokenizeByWhitespace().let {
        val delIdx: Int = Random.nextInt(it.size - 1)
        it.subList(0, delIdx) + it.subList(delIdx + 1, it.size)
      }.joinToString(" ")

    generateSequence {
      measureTime {
        val cfg = generateRandomCFG().parseCFG().freeze()
        val results = cfg.enumSeq(List(30) { "_" }).filter { 20 < it.length }.take(10).toList()
        val corruptedResults = results.map { if (Random.nextBoolean()) it else it.deleteRandomSingleWord() }
        preparseParseableLines(cfg, corruptedResults.joinToString("\n"))
      }
    }.take(100).toList().map { it.toDouble(DurationUnit.MILLISECONDS) }
      .also { println("Average time: ${it.average().round(3)}ms, total time ${it.sum().round(3)}ms") }
  }

/*
./gradlew jvmTest --tests "ai.hypergraph.kaliningraph.parsing.SetValiantTest.testPythonRepairs"
*/
  @Test
  fun testPythonRepairs() {
    val refStr = "NAME = ( NAME"
    val refLst = refStr.tokenizeByWhitespace()
    val template = List(refLst.size + 3) { "_" }
    println("Solving: $template")
    measureTime {
      Grammars.seq2parsePythonCFG.enumSeq(template)
        .map { it to levenshtein(it, refStr) }
        .filter { it.second < 4 }.distinct().take(100)
        .sortedWith(compareBy({ it.second }, { it.first.length }))
        .onEach { println("Δ=${it.second}: ${it.first}") }
//        .onEach { println("Δ=${levenshtein(it, refStr)}: $it") }
        .toList()
        .also { println("Found ${it.size} solutions!") }
    }.also { println("Finished in ${it.inWholeMilliseconds}ms.") }
  }
}