package com.kaleaon.mnxmindmaker.ui.mindmap

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.kaleaon.mnxmindmaker.model.MindEdge
import com.kaleaon.mnxmindmaker.model.MindGraph
import com.kaleaon.mnxmindmaker.model.MindNode
import com.kaleaon.mnxmindmaker.model.NodeType

/**
 * Custom View that renders the mind map canvas.
 *
 * Supports:
 *  - Pinch-to-zoom and pan gestures
 *  - Tap to select a node
 *  - Long-press drag to move nodes
 *  - Visual rendering of nodes by [NodeType] with color coding
 *  - Edge drawing between connected nodes
 */
class MindMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    var graph: MindGraph? = null
        set(value) {
            field = value
            invalidate()
        }

    var onNodeSelected: ((MindNode?) -> Unit)? = null
    var onNodeMoved: ((String, Float, Float) -> Unit)? = null

    // Viewport transform
    private var scaleX = 1f
    private var scaleY = 1f
    private var translateX = 0f
    private var translateY = 0f

    // Interaction state
    private var selectedNodeId: String? = null
    private var dragNodeId: String? = null
    private var lastDragX = 0f
    private var lastDragY = 0f

    // Node hit rect cache
    private val nodeRects = mutableMapOf<String, RectF>()

    // Paints
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#BDBDBD")
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }
    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val nodeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.WHITE
    }
    private val selectedBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        color = Color.YELLOW
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
        textAlign = Paint.Align.CENTER
    }
    private val subtextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E0E0E0")
        textSize = 20f
        textAlign = Paint.Align.CENTER
    }

    // Gesture detectors
    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val factor = detector.scaleFactor
                scaleX = (scaleX * factor).coerceIn(0.2f, 4f)
                scaleY = (scaleY * factor).coerceIn(0.2f, 4f)
                invalidate()
                return true
            }
        })

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val hit = hitTest(e.x, e.y)
                selectedNodeId = hit?.id
                onNodeSelected?.invoke(hit)
                invalidate()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                val hit = hitTest(e.x, e.y)
                if (hit != null) {
                    dragNodeId = hit.id
                    lastDragX = e.x
                    lastDragY = e.y
                }
            }

            override fun onScroll(
                e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float
            ): Boolean {
                if (dragNodeId == null) {
                    translateX -= distanceX
                    translateY -= distanceY
                    invalidate()
                }
                return true
            }
        })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        // Handle drag movement
        if (event.action == MotionEvent.ACTION_MOVE && dragNodeId != null) {
            val dx = (event.x - lastDragX) / scaleX
            val dy = (event.y - lastDragY) / scaleY
            val node = graph?.nodes?.firstOrNull { it.id == dragNodeId }
            if (node != null) {
                val newX = node.x + dx
                val newY = node.y + dy
                onNodeMoved?.invoke(dragNodeId!!, newX, newY)
                lastDragX = event.x
                lastDragY = event.y
            }
        }
        if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
            dragNodeId = null
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val g = graph ?: return

        canvas.save()
        canvas.translate(translateX, translateY)
        canvas.scale(scaleX, scaleY)

        // Draw background grid
        drawGrid(canvas)

        // Draw edges first (behind nodes)
        g.edges.forEach { edge -> drawEdge(canvas, g, edge) }

        // Draw nodes
        nodeRects.clear()
        g.nodes.forEach { node -> drawNode(canvas, node) }

        canvas.restore()
    }

    private fun drawGrid(canvas: Canvas) {
        val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1E1E2E")
            strokeWidth = 1f
            style = Paint.Style.STROKE
        }
        val step = 80f
        val startX = (-translateX / scaleX) - step
        val startY = (-translateY / scaleY) - step
        val endX = startX + (width / scaleX) + step * 2
        val endY = startY + (height / scaleY) + step * 2
        var x = startX - (startX % step)
        while (x < endX) { canvas.drawLine(x, startY, x, endY, gridPaint); x += step }
        var y = startY - (startY % step)
        while (y < endY) { canvas.drawLine(startX, y, endX, y, gridPaint); y += step }
    }

    private fun drawEdge(canvas: Canvas, g: MindGraph, edge: MindEdge) {
        val from = g.nodes.firstOrNull { it.id == edge.fromNodeId } ?: return
        val to = g.nodes.firstOrNull { it.id == edge.toNodeId } ?: return
        val path = Path()
        path.moveTo(from.x, from.y)
        val cpX = (from.x + to.x) / 2f
        val cpY = (from.y + to.y) / 2f - 40f
        path.quadTo(cpX, cpY, to.x, to.y)
        canvas.drawPath(path, edgePaint)
    }

    private fun drawNode(canvas: Canvas, node: MindNode) {
        val nodeW = 160f
        val nodeH = 64f
        val rect = RectF(node.x - nodeW / 2, node.y - nodeH / 2, node.x + nodeW / 2, node.y + nodeH / 2)
        nodeRects[node.id] = rect

        val color = parseHexColor(node.type.colorHex)
        nodePaint.color = color
        canvas.drawRoundRect(rect, 16f, 16f, nodePaint)

        val borderPaint = if (node.id == selectedNodeId) selectedBorderPaint else nodeBorderPaint
        canvas.drawRoundRect(rect, 16f, 16f, borderPaint)

        // Label
        val maxChars = 18
        val displayLabel = if (node.label.length > maxChars) node.label.take(maxChars) + "…" else node.label
        canvas.drawText(displayLabel, node.x, node.y + 6f, textPaint)

        // Type tag
        canvas.drawText(node.type.displayName, node.x, node.y + 28f, subtextPaint)
    }

    private fun hitTest(touchX: Float, touchY: Float): MindNode? {
        // Convert touch coords to canvas coords
        val cx = (touchX - translateX) / scaleX
        val cy = (touchY - translateY) / scaleY
        return graph?.nodes?.firstOrNull { node ->
            nodeRects[node.id]?.contains(cx, cy) == true
        }
    }

    private fun parseHexColor(hex: String): Int = Color.parseColor(hex)

    /** Reset viewport to center */
    fun resetView() {
        scaleX = 1f; scaleY = 1f; translateX = 0f; translateY = 0f; invalidate()
    }
}
