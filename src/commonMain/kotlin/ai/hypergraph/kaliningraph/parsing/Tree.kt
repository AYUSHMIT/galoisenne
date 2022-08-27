package ai.hypergraph.kaliningraph.parsing

import ai.hypergraph.kaliningraph.containsNonterminal
import ai.hypergraph.kaliningraph.graphs.LGVertex
import ai.hypergraph.kaliningraph.graphs.LabeledGraph
import ai.hypergraph.kaliningraph.image.escapeHTML
import ai.hypergraph.kaliningraph.tensor.FreeMatrix

typealias TreeMatrix = FreeMatrix<Forest>
typealias Forest = Set<Tree>

class Tree constructor(
  val root: String,
  val terminal: String? = null,
  vararg val children: Tree,
  val span: IntRange = children.fold(Int.MAX_VALUE to Int.MIN_VALUE) { (a, b), t ->
    minOf(a, t.span.first) to maxOf(b, t.span.last)}.let { it.first..it.second }
) {
  override fun toString() = root
  override fun hashCode() = root.hashCode()
  override fun equals(other: Any?) = hashCode() == other.hashCode()

  fun toGraph(j: String = "0"): LabeledGraph =
    LabeledGraph { LGVertex(root, "$root.$j").let { it - it } } +
      children.foldIndexed(
        LabeledGraph {
          children.forEachIndexed { i, it ->
            LGVertex(root, "$root.$j") - LGVertex(it.root, "${it.root}.$j.$i")
          }
        }
      ) { i, acc, it -> acc + it.toGraph("$j.$i") }

  fun prettyPrint(
    buffer: String = "",
    prefix: String = "",
    childrenPrefix: String = "",
  ): String =
    if (children.isEmpty()) (buffer + prefix + "${terminal?.htmlify()} [${span.first}]\n")
    else children.foldIndexed("$buffer$prefix" +
      root.htmlify() + (if(-1 !in span) " [$span]" else "") + "\n") { i: Int, acc: String, it: Tree ->
      if (i == children.size - 1)
        it.prettyPrint(acc + "", "$childrenPrefix└── ", "$childrenPrefix    ")
      else it.prettyPrint(acc, "$childrenPrefix├── ", "$childrenPrefix│   ")
    }

  private fun String.htmlify() =
    replace('<', '⟨').replace('>', '⟩')
}