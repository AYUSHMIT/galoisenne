package ai.hypergraph.kaliningraph.parsing

import ai.hypergraph.kaliningraph.*
import ai.hypergraph.kaliningraph.sampling.choose
import ai.hypergraph.kaliningraph.types.powerset
import kotlin.math.absoluteValue
import ai.hypergraph.kaliningraph.types.cache
import ai.hypergraph.kaliningraph.types.isStrictSubsetOf

typealias Reconstructor = MutableList<Pair<Σᐩ, Σᐩ>>
typealias Mutator = (Σᐩ) -> Sequence<Σᐩ>

// Terminals which are blocked from synthesis
val CFG.blocked: MutableSet<Σᐩ> by cache { mutableSetOf() }

fun repair(
  prompt: Σᐩ,
  cfg: CFG,
  coarsen: Σᐩ.() -> Σᐩ = { this },
  uncoarsen: Σᐩ.(Σᐩ) -> Σᐩ = { this },
  synthesizer: CFG.(List<Σᐩ>) -> Sequence<Σᐩ>,
  blockers: Set<Σᐩ> = setOf()
): List<Σᐩ> {
  val coarsened = prompt.coarsen()
  val tokens = coarsened.tokenizeByWhitespace()
  val tokensWithHoles = tokens.map { if (it in cfg.terminals) it else "_" }
  if (80 < tokensWithHoles.size) return listOf() // Pass on long samples

  val (parseForest, stubs) = cfg.parseWithStubs(coarsened)

  val sanitized: Σᐩ = tokensWithHoles.joinToString(" ")

  val exclude = stubs.allIndicesInsideParseableRegions() +
    tokensWithHoles.indices.filter { i -> tokensWithHoles[i] in blockers }

  val maxResults = 10

  val variations: List<Mutator> =
    listOf({
      it.multiTokenSubstitutionsAndInsertions(
        numberOfEdits = 2,
        exclusions = exclude,
        fishyLocations = listOf(tokens.size)
      )
    })

  val repairs: List<Σᐩ> =
    sanitized.synthesizeWithVariations(
      cfg = cfg,
      variations = variations,
      synthesizer = synthesizer,
      allowNTs = false
//      enablePruning = true
    ).take(maxResults).toList().sortedWith(tokens.ranker()).map { it.uncoarsen(prompt) }

  return repairs
}

fun List<Σᐩ>.ranker(): Comparator<Σᐩ> =
  compareBy(tokenwiseEdits(this)).thenBy { it.length }

private fun tokenwiseEdits(tokens: List<Σᐩ>): (Σᐩ) -> Comparable<*> =
  { levenshtein(tokens.filterNot { it.containsHole() }, it.tokenizeByWhitespace()) }

// Generates a lazy sequence of mutations for a broken string and feeds them to the synthesizer
fun Σᐩ.synthesizeWithVariations(
  cfg: CFG,
  allowNTs: Boolean = true,
  enablePruning: Boolean = false,
  variations: List<Mutator> = listOf({ sequenceOf() }),
  updateProgress: (Σᐩ) -> Unit = {},
  skipWhen: (List<Σᐩ>) -> Boolean = { false },
  synthesizer: CFG.(List<Σᐩ>) -> Sequence<Σᐩ>
): Sequence<Σᐩ> {
  val cfg_ = if (!allowNTs) cfg.noNonterminalStubs else cfg

  val (stringToSolve, reconstructor) =
    if (enablePruning) cfg.prune(this) else this to mutableListOf()
  if (this != stringToSolve) println("Before pruning: $this\nAfter pruning: $stringToSolve")

  val allVariants: Sequence<Σᐩ> =
    variations.fold(sequenceOf(stringToSolve)) { a, b -> a + b(this@synthesizeWithVariations) }
      .distinct().rejectTemplatesContainingImpossibleBigrams(cfg_)

  return allVariants.map { updateProgress(it); it }
    .flatMap {
      val variantTokens = tokenize(it)
      if (skipWhen(variantTokens)) emptySequence()
      else cfg_.run { synthesizer(variantTokens) }
        .ifEmpty {
          variantTokens.rememberImpossibleBigrams(cfg_, synthesizer)
          emptySequence()
        }
    }.distinct()
}

/**
 * Attempts to reduce parsable subsequences into a single token to reduce total
 * token count, e.g. ( w ) + _ => <S> + _ resulting in two fewer tokens overall.
 * Consider 3 + 5 * _ != <S> * _ for checked arithmetic, so context-insensitive
 * pruning is not always sound, thus we should err on the side of caution.
 *
 * TODO: A proper solution requires ruling out whether the left- and right-
 *       quotients of the root nonterminal ever yield another derivation.
 */

fun CFG.prune(
  string: Σᐩ,
  minimumWidth: Int = 4,
  // Maps nonterminal stubs from pruned branches back to original string
  reconstructor: Reconstructor =
    tokenize(string).filter { it.isNonterminalStubIn(this) }
      .map { it to it }.toMutableList()
): Pair<Σᐩ, Reconstructor> {
  val tokens = tokenize(string)
  val stubs = parseWithStubs(string).second
    .fold(setOf<Tree>()) { acc, t ->
      if (acc.any { t.span isStrictSubsetOf it.span }) acc else acc + t
    }.sortedBy { it.span.first }

  val treesToBeChopped =
    stubs.filter { "START" in equivalenceClass(setOf(it.root)) }
      .map { it.span to it }.let {
        val (spans, trees) = it.unzip()
        // Find trees corresponding to ranges which have an unambiguous parse tree
        trees.filter { tree ->
          minimumWidth < tree.span.run { last - first } &&
            spans.filter { it != tree.span }
              .none { tree.span.intersect(it).isNotEmpty() }
        }
      }//.onEach { println(it.prettyPrint()) }

  if (treesToBeChopped.isEmpty()) string to reconstructor

  var totalPruned = 0
  var previousNonterminals = 0
  val prunedString = tokens.indices.mapNotNull { i ->
    val possibleTree = treesToBeChopped.firstOrNull { i in it.span }
    if (possibleTree != null)
      if (i == possibleTree.span.first) "<${possibleTree.root}>".also {
        val (a, b) = it to possibleTree.contents()
        println("Reduced: $b => $a")
        reconstructor.add(previousNonterminals++, a to b)
      } else { totalPruned++; null }
    else tokens[i].also { if (it.isNonterminalStubIn(this)) previousNonterminals++ }
  }.joinToString(" ")

  println("Pruned $totalPruned tokens in total")
  return if (totalPruned == 0) string to reconstructor
  else prune(prunedString, minimumWidth, reconstructor)
}

// TODO: implement complete substring decider
// https://nokyotsu.com/me/papers/cic01.pdf
// https://cs.stackexchange.com/questions/154130/minimal-length-strings-which-are-substrings-of-no-string-in-a-given-cfl
// These strings must never appear in any length-k string in the language defined by this grammar
val CFG.impossibleBigrams by cache { mutableMapOf<Int, MutableSet<Σᐩ>>() }
// Underapproximates impossible substrings for a sketch template of a given length by tracking
// the impossible substrings that cannot fit inside an equal- or longer-length string, i.e.,
// if a string does not fit in Σ^100, then it definitely will not fit in Σ^k<100. In the worst case
// it will be a false negative and we do unnecessary work trying to solve an impossible template.
fun Map<Int, Set<Σᐩ>>.unableToFitInside(k: Int): Set<Σᐩ> =
  values.flatten().toSet() // May not work for ngrams but for bigrams it should be fine
//  keys.filter { k <= it }.flatMap { this[it] ?: setOf() }.toSet()

// These strings all appear in an arbitrary-length string in the language defined by this grammar
val CFG.possibleBigrams by cache { mutableSetOf<Σᐩ>() }

fun Sequence<Σᐩ>.rejectTemplatesContainingImpossibleBigrams(cfg: CFG): Sequence<Σᐩ> =
  filter { sketch ->
    val numTokens = sketch.count { it == ' ' }
    cfg.impossibleBigrams.unableToFitInside(numTokens).none { iss ->
      (iss in sketch).also {
        if (it) println("$sketch rejected because it contains an impossible bigram: $iss")
      }
    }
  }

fun List<Σᐩ>.rememberImpossibleBigrams(
  cfg: CFG,
  synthesizer: CFG.(List<Σᐩ>) -> Sequence<Σᐩ>
) {
  windowed(2).asSequence().filter {
    it.all { it in cfg.terminals } && it.joinToString(" ") !in cfg.possibleBigrams + cfg.impossibleBigrams
  }.forEach {
    val holes = List((size / 2).coerceIn(4..8)) { "_" }.joinToString(" ")
    val substring = it.joinToString(" ")
    val tokens = tokenize("$holes $substring $holes")
    if (cfg.synthesizer(tokens).firstOrNull() == null)
      cfg.impossibleBigrams.getOrPut(tokens.size) { mutableSetOf() }.add(substring)
    else cfg.possibleBigrams.add(substring)
  }
}

// TODO: Instead of haphazardly splattering holes everywhere and hoping to hit the lottery
//       we should work out a principled way to localize holes using the language quotient.
//       For example, we can do this bottom-up, by localizing substrings which are known to
//       be outside the language, e.g., for the following grammar and string:
//             E → E+E | E*E | (E) | x                      (+)+x*x+x+(x*x)
//       we know that the substring (+) cannot be in the grammar, so we can infer (_+_).
//             https://nokyotsu.com/me/papers/cic01.pdf
//
// Idea: Generate minimal strings which cannot be repaired by left or right insertion,
//       these will become our initial set. Whenever we encounter one of these substrings
//       in the candidate string, we know that without repairing that part of the string
//       candidate, its full string can never be in the language defined by the given CFG.
//
//       { S | |S| < k & !∃ S' ∈ L(CFG) s.t. S is a substring of S' }
//       This will help us refine where the repairs must happen.

fun List<Tree>.allIndicesInsideParseableRegions(): Set<Int> =
  map { it.span }.filter { 3 < it.last - it.first }
    .flatMap { (it.first + 1) until it.last }.toSet()

/*
 * Generates all single character replacements and insertions.
 * Original: www
 * Variants: _www w_ww ww_w www_
 *           _ww w_w ww_
 */

fun Σᐩ.singleTokenSubtitutionsAndInsertions(): Sequence<Σᐩ> =
  multiTokenSubstitutionsAndInsertions(numberOfEdits = 1)

fun Σᐩ.multiTokenSubstitutionsAndInsertions(
  tokens: List<Σᐩ> = tokenizeByWhitespace(),
  padded: List<Σᐩ> = listOf("", *tokens.toTypedArray(), ""),
  numberOfEdits: Int = minOf(2, tokens.size),
  exclusions: Set<Int> = setOf(),
  // Sorted list of locations believed to be erroneous
  fishyLocations: List<Int> = listOf(tokens.size)
): Sequence<Σᐩ> =
  allSubstitutions(padded.indices.toSet() - exclusions.map { it + 1 }.toSet(), numberOfEdits, fishyLocations)
    .map { idxs -> padded.substitute(idxs) { "_ _" } }
//    .apply {
//      println("Exclusions: ${tokens.mapIndexed { i, it -> if (i !in exclusions) "_".padEnd(it.length) else it }.joinToString(" ")}")
//      println("Fishy toks: ${tokens.mapIndexed { i, it -> if (i in fishyLocations) "_".padEnd(it.length) else it }.joinToString(" ")}")
//    }

fun allSubstitutions(eligibleIndices: Set<Int>, numEdits: Int, fishyLocations: List<Int>) =
  eligibleIndices.sortedWith(
    compareBy<Int> { a -> fishyLocations.minOf { b -> (a - b).absoluteValue } }
      .thenBy { (it - fishyLocations.first()).absoluteValue }
  ).let { sortedIndices -> setOf(1, numEdits)
    .asSequence().flatMap { sortedIndices.choose(it) } }
//  setOf(1, numEdits).asSequence()
//    .flatMap { eligibleIndices.choose(it) }.map { it.sorted().toSet() }
//    .sortedWith(
//      compareBy<Set<Int>> { it.size }
//        // Out of all chosen indices, how far apart from its nearest fishy neighbor
//        // is the chosen index whose nearest fishy neighbor is the farthest apart?
//        .thenBy { it.maxOf { a -> fishyLocations.minOf { b -> abs(a - b) } } }
//  //  .thenBy { it.sumOf { a -> fishyLocations.indices.minBy { abs(a - fishyLocations[it]) } } } // Sort by precedence?
//        .thenBy { it.fold(0 to it.first()) { (a, b), it -> a + abs(it - b) to it }.first } // Sort by dispersion?
//        .thenBy { a -> a.sumOf { abs(fishyLocations.first() - it) } } // Sort by distance to first fishy location (caret)
//    ).map { it.toSet() }

private fun List<Σᐩ>.substitute(idxs: Set<Int>, sub: (Σᐩ) -> Σᐩ): Σᐩ =
  mapIndexed { i, it -> if (i !in idxs) it else sub(it) }.joinToString(" ").trim()

fun Σᐩ.tokenizeByWhitespace(): List<Σᐩ> =
  split(Regex("\\s+")).filter { it.isNotBlank() }

/*
 * Treats contiguous underscores as a single hole and lazily enumerates every
 * hole configuration in the powerset of all holes within a snippet.
 * Original: ___w__w_w__w___ -> _w_w_w_w_
 * Variants: _wwww  _w_www _w_w_ww ... _w_w_w_w_
 *           w_www  _ww_ww _w_ww_w
 *           ww_ww  _www_w _w_www_
 *           ...    ...    ...
 */

fun Σᐩ.everySingleHoleConfig(): Sequence<Σᐩ> {
  val new = replace(Regex("(_( )*)+"), "_ ")
  val toks = new.tokenizeByWhitespace()
  val indices = toks.indices.filter { toks[it] == "_" }.powerset()
  return indices.map { ids -> toks.drop(setOf("_"), ids).joinToString(" ") }
}

/*
 * Lazily enumerates all underscores chunkings in order of increasing length up
 * to the lesser of (1) its original size or (2) the longest underscore chunk.
 * Original: ___w__w_w__w___
 * Variants: _w_w_w_w_
 *           __w__w_w__w__
 *           ___w__w_w__w___
 */

fun Σᐩ.mergeHoles(): Σᐩ =
  replace(Regex("\\s+"), " ")
    .replace(Regex("(?<=_)\\s(?=_)"), "")

fun Σᐩ.increasingLengthChunks(): Sequence<Σᐩ> {
  val chunks = mergeHoles().split(Regex("((?<=[^_])|(?=[^_]))"))
  return (2..chunks.maxOf { it.length }).asSequence()
    .map { l -> chunks.joinToString("") { if (it.containsHole()) it.take(l).toCharArray().joinToString(" ") else it } }
}
