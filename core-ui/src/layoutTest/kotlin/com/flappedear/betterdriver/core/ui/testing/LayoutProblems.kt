package com.flappedear.betterdriver.core.ui.testing

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow

// What assertLayoutClean looks for (FBBD-40), read from the semantics tree.

/** What [assertLayoutClean] finds, one line each. */
fun SemanticsNodeInteractionsProvider.layoutProblems(): List<String> {
    val root = onRoot(useUnmergedTree = true).fetchSemanticsNode()
    val width = root.size.width.toFloat()
    val height = root.size.height.toFloat()
    val nodes = onAllNodes(AnyNode, useUnmergedTree = true).fetchSemanticsNodes()
    val problems = ArrayList<String>()
    for (node in nodes) {
        node.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange)?.let { range ->
            if (range.maxValue() > Slack) problems += "${describe(node)} scrolls sideways by ${range.maxValue()} px"
        }
        if (node.size.width == 0 || node.size.height == 0) continue
        val left = node.positionInRoot.x
        val right = left + node.size.width
        if (left < -Slack || right > width + Slack) {
            problems += "${describe(node)} sticks out of the window: x $left to $right of $width px"
        }
        if (isText(node)) problems += textProblems(node, height)
    }
    problems += overlaps(nodes.filter(::isText))
    return problems
}

private fun textProblems(node: SemanticsNode, windowHeight: Float): List<String> {
    val problems = ArrayList<String>()
    val name = describe(node)
    layoutOf(node)?.let { layout ->
        if (layout.hasVisualOverflow && layout.layoutInput.overflow != TextOverflow.Ellipsis) {
            problems += "$name does not fit its box and is clipped"
        }
    }
    val bounds = node.boundsInRoot
    val scrolls = insideVerticalScroll(node)
    if (bounds.width < node.size.width - Slack) {
        problems += "$name is cut off sideways: ${bounds.width} of ${node.size.width} px shown"
    }
    if (!scrolls) {
        if (bounds.height < node.size.height - Slack) {
            problems += "$name is cut off: ${bounds.height} of ${node.size.height} px shown"
        }
        if (node.positionInRoot.y + node.size.height > windowHeight + Slack) {
            problems += "$name reaches below the window and nothing scrolls"
        }
    }
    return problems
}

/** Text nodes that overlap, as pairs; a node and its own descendants are not compared. */
private fun overlaps(texts: List<SemanticsNode>): List<String> {
    val problems = ArrayList<String>()
    for (i in texts.indices) {
        for (j in i + 1 until texts.size) {
            val a = texts[i]
            val b = texts[j]
            if (related(a, b)) continue
            val overlap = a.boundsInRoot.intersect(b.boundsInRoot)
            if (overlap.width > Slack && overlap.height > Slack) {
                problems += "${describe(a)} and ${describe(b)} overlap by ${size(overlap)}"
            }
        }
    }
    return problems
}

private fun related(a: SemanticsNode, b: SemanticsNode): Boolean = isAncestor(a, b) || isAncestor(b, a)

private fun isAncestor(ancestor: SemanticsNode, node: SemanticsNode): Boolean {
    var p = node.parent
    while (p != null) {
        if (p.id == ancestor.id) return true
        p = p.parent
    }
    return false
}

private fun insideVerticalScroll(node: SemanticsNode): Boolean {
    var p = node.parent
    while (p != null) {
        if (p.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null) return true
        p = p.parent
    }
    return false
}

/** A text: a Text's own node, or a node that reads a value as one (NumberText's content description). */
private fun isText(node: SemanticsNode): Boolean =
    node.config.getOrNull(SemanticsProperties.Text)?.isNotEmpty() == true ||
        (node.children.isEmpty() && node.config.getOrNull(SemanticsProperties.ContentDescription)?.isNotEmpty() == true)

/** The text's layout, from the node's GetTextLayoutResult action (Text has it; other nodes do not). */
fun layoutOf(node: SemanticsNode): TextLayoutResult? {
    val results = ArrayList<TextLayoutResult>()
    val got = node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results) == true
    return if (got) results.firstOrNull() else null
}

private fun describe(node: SemanticsNode): String {
    val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ")
        ?: node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")
        ?: node.config.getOrNull(SemanticsProperties.TestTag)
    return if (text != null) "\"$text\"" else "node ${node.id}"
}

private fun size(r: Rect) = "%.0f x %.0f px".format(r.width, r.height)

private val AnyNode = SemanticsMatcher("any node") { true }

/** Rounding of positions to whole pixels. */
private const val Slack = 1f
