package ai.hypergraph.kaliningraph.notebook

import ai.hypergraph.kaliningraph.graphs.Gate
import ai.hypergraph.kaliningraph.image.matToBase64Img
import ai.hypergraph.kaliningraph.tensor.Matrix
import ai.hypergraph.kaliningraph.types.*
import ai.hypergraph.kaliningraph.visualization.html
import org.jetbrains.kotlinx.jupyter.api.HTML
import org.jetbrains.kotlinx.jupyter.api.libraries.JupyterIntegration

internal class Integration: JupyterIntegration() {
  override fun Builder.onLoaded() {
    listOf(
      "ai.hypergraph.kaliningraph.*",
      "ai.hypergraph.kaliningraph.graphs.*",
      "ai.hypergraph.kaliningraph.types.*",
      "ai.hypergraph.kaliningraph.visualization.*",
      "ai.hypergraph.kaliningraph.tensor.*",
    ).forEach { import(it) }

    render<Matrix<*, *, *>> { HTML("<img src=\"${it.matToBase64Img()}\" height=\"200\" width=\"200\"/>") }
    render<Graph<*, *, *>> { HTML(it.html()) }
    render<Gate> { HTML(it.graph.html()) }

    // https://github.com/Kotlin/kotlin-jupyter/blob/master/docs/libraries.md#integration-using-kotlin-api
    // https://github.com/nikitinas/dataframe/blob/master/src/main/kotlin/org/jetbrains/dataframe/jupyter/Integration.kt
    // https://github.com/mipt-npm/visionforge/blob/dev/demo/jupyter-playground/src/main/kotlin/hep/dataforge/playground/VisionForgePlayGroundForJupyter.kt
  }
}