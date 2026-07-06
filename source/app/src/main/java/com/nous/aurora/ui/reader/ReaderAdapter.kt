package com.nous.aurora.ui.reader

import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.nous.aurora.R
import com.nous.aurora.data.model.ContentBlock
import com.nous.aurora.util.AccessibilityUtil
import com.nous.aurora.util.SyntaxHighlighter

class ReaderAdapter(
    private val blocks: List<ContentBlock>,
    private val annotationCounts: Map<Int, Int> = emptyMap(),
    private val onItemTap: (ContentBlock, Int) -> Unit,
    private val announceParagraphNumber: () -> Boolean = { false }
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_PARAGRAPH = 0
        private const val TYPE_HEADING = 1
        private const val TYPE_LINK = 2
        private const val TYPE_IMAGE = 3
        private const val TYPE_SEPARATOR = 4
        private const val TYPE_TABLE = 5
        private const val TYPE_FORMULA = 6
        private const val TYPE_CODE = 7
    }

    override fun getItemViewType(position: Int): Int = when (blocks[position]) {
        is ContentBlock.Paragraph -> TYPE_PARAGRAPH
        is ContentBlock.Heading -> TYPE_HEADING
        is ContentBlock.Link -> TYPE_LINK
        is ContentBlock.Image -> TYPE_IMAGE
        is ContentBlock.Table -> TYPE_TABLE
        is ContentBlock.Formula -> TYPE_FORMULA
        is ContentBlock.Code -> TYPE_CODE
        else -> TYPE_SEPARATOR
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADING -> HeadingViewHolder(inflater.inflate(R.layout.item_heading, parent, false))
            TYPE_LINK -> LinkViewHolder(inflater.inflate(R.layout.item_link, parent, false))
            TYPE_IMAGE -> ImageViewHolder(inflater.inflate(R.layout.item_image, parent, false))
            TYPE_TABLE -> TableViewHolder(inflater.inflate(R.layout.item_table, parent, false))
            TYPE_FORMULA -> FormulaViewHolder(inflater.inflate(R.layout.item_formula, parent, false))
            TYPE_CODE -> CodeViewHolder(inflater.inflate(R.layout.item_code, parent, false))
            TYPE_SEPARATOR -> SeparatorViewHolder(inflater.inflate(R.layout.item_separator, parent, false))
            else -> ParagraphViewHolder(inflater.inflate(R.layout.item_paragraph, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val block = blocks[position]
        when (holder) {
            is ParagraphViewHolder -> holder.bind(block as ContentBlock.Paragraph, position)
            is HeadingViewHolder -> holder.bind(block as ContentBlock.Heading, position)
            is LinkViewHolder -> holder.bind(block as ContentBlock.Link, position)
            is ImageViewHolder -> holder.bind(block as ContentBlock.Image, position)
            is TableViewHolder -> holder.bind(block as ContentBlock.Table, position)
            is FormulaViewHolder -> holder.bind(block as ContentBlock.Formula, position)
            is CodeViewHolder -> holder.bind(block as ContentBlock.Code, position)
            is SeparatorViewHolder -> holder.bind(position)
        }
    }

    override fun getItemCount(): Int = blocks.size

    /** 为 itemView 设置无障碍点击：让 TalkBack 双击也能触发 onItemTap */
    private fun setupAccessibilityClick(view: View, block: ContentBlock, index: Int) {
        ViewCompat.replaceAccessibilityAction(
            view,
            AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK,
            "打开阅读菜单"
        ) { _, _ ->
            onItemTap(block, index)
            true
        }
    }

    inner class ParagraphViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textView: TextView = itemView.findViewById(R.id.tv_paragraph)

        fun bind(block: ContentBlock.Paragraph, index: Int) {
            textView.text = block.text
            val prefix = if (announceParagraphNumber()) "第${index + 1}段，" else ""
            val desc = "$prefix${block.text}"
            AccessibilityUtil.setAccessibilityFocusable(itemView, desc)

            val count = annotationCounts[index] ?: 0
            if (count > 0) {
                textView.setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.ic_annotation, 0)
                itemView.contentDescription = "$desc，有${count}条批注"
            } else {
                textView.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
            }

            itemView.setOnClickListener { onItemTap(block, index) }
            setupAccessibilityClick(itemView, block, index)
        }
    }

    inner class HeadingViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textView: TextView = itemView.findViewById(R.id.tv_heading)

        fun bind(block: ContentBlock.Heading, index: Int) {
            textView.text = block.text
            textView.textSize = when (block.level) {
                1 -> 24f; 2 -> 20f; 3 -> 18f; else -> 16f
            }
            val desc = "标题，${block.text}"
            AccessibilityUtil.setAccessibilityFocusable(itemView, desc, isHeading = true)
            itemView.setOnClickListener { onItemTap(block, index) }
            setupAccessibilityClick(itemView, block, index)
        }
    }

    inner class LinkViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textView: TextView = itemView.findViewById(R.id.tv_link)

        fun bind(block: ContentBlock.Link, index: Int) {
            textView.text = block.text
            val desc = "${block.text}链接"
            AccessibilityUtil.setAccessibilityFocusable(itemView, desc)
            itemView.setOnClickListener { onItemTap(block, index) }
            setupAccessibilityClick(itemView, block, index)
        }
    }

    inner class ImageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val imageView: ImageView = itemView.findViewById(R.id.iv_image)
        private val labelView: TextView = itemView.findViewById(R.id.tv_image_label)

        fun bind(block: ContentBlock.Image, index: Int) {
            val label = block.text.ifBlank { "无法描述的图片" }
            labelView.text = label

            val bitmap = block.imageData?.takeIf { it.isNotEmpty() }?.let {
                BitmapFactory.decodeByteArray(it, 0, it.size)
            }
            if (bitmap != null) {
                imageView.load(bitmap) {
                    crossfade(true)
                    placeholder(android.R.drawable.ic_menu_gallery)
                    error(android.R.drawable.ic_menu_close_clear_cancel)
                }
                imageView.visibility = View.VISIBLE
            } else {
                imageView.visibility = View.GONE
            }

            val desc = "图片，$label"
            AccessibilityUtil.setAccessibilityFocusable(itemView, desc)
            itemView.setOnClickListener { onItemTap(block, index) }
            setupAccessibilityClick(itemView, block, index)
        }
    }

    inner class TableViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val headerRow: LinearLayout = itemView.findViewById(R.id.table_header)
        private val bodyContainer: LinearLayout = itemView.findViewById(R.id.table_body)

        fun bind(block: ContentBlock.Table, index: Int) {
            val headers = block.headers
            val rows = block.rows
            val allRows = (listOf(headers) + rows).filter { it.isNotEmpty() }
            if (allRows.isEmpty()) return

            val colCount = allRows.maxOf { it.size }
            val colWidths = IntArray(colCount)
            for (row in allRows) {
                for (c in row.indices) { colWidths[c] = maxOf(colWidths[c], row[c].length) }
            }

            val ctx = itemView.context
            val dp4 = (4 * ctx.resources.displayMetrics.density).toInt()
            val dp8 = (8 * ctx.resources.displayMetrics.density).toInt()

            val textColorPrimary = androidx.core.content.ContextCompat.getColor(ctx, android.R.color.primary_text_dark)
            val textColorSecondary = androidx.core.content.ContextCompat.getColor(ctx, android.R.color.secondary_text_dark)
            val rowAltBg = if ((ctx.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES) 0x20FFFFFF.toInt() else 0x10000000.toInt()

            headerRow.removeAllViews()
            if (headers.isNotEmpty()) {
                for ((ci, cell) in headers.withIndex()) {
                    val tv = TextView(ctx).apply {
                        text = cell; textSize = 13f; setTypeface(Typeface.DEFAULT_BOLD)
                        setPadding(dp8, dp4, dp8, dp4); setTextColor(textColorPrimary)
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    headerRow.addView(tv)
                }
            }

            bodyContainer.removeAllViews()
            for ((ri, row) in rows.withIndex()) {
                val rowLayout = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp4, 0, dp4)
                    if (ri % 2 == 1) setBackgroundColor(rowAltBg)
                }
                for (c in 0 until colCount) {
                    val cellText = row.getOrElse(c) { "" }
                    val tv = TextView(ctx).apply {
                        text = cellText; textSize = 13f; setPadding(dp8, dp4, dp8, dp4)
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        if (cellText.any { it.isDigit() } && cellText.length <= 10) {
                            gravity = android.view.Gravity.END
                        }
                    }
                    rowLayout.addView(tv)
                }
                bodyContainer.addView(rowLayout)
            }

            val desc = buildString {
                append("表格，${colCount}列${rows.size}行。")
                if (headers.isNotEmpty()) append("表头：${headers.joinToString("，")}。")
                for ((ri, row) in rows.withIndex()) append("第${ri + 1}行：${row.joinToString("，")}。")
            }
            itemView.contentDescription = desc
            itemView.setOnClickListener { onItemTap(block, index) }
            setupAccessibilityClick(itemView, block, index)
        }
    }

    inner class FormulaViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textView: TextView = itemView.findViewById(R.id.tv_formula)

        fun bind(block: ContentBlock.Formula, index: Int) {
            textView.text = if (block.display) "[公式] ${block.text}" else block.text
            val desc = "数学公式，${block.text}"
            AccessibilityUtil.setAccessibilityFocusable(itemView, desc)
            itemView.setOnClickListener { onItemTap(block, index) }
            setupAccessibilityClick(itemView, block, index)
        }
    }

    inner class CodeViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textView: TextView = itemView.findViewById(R.id.tv_code)

        fun bind(block: ContentBlock.Code, index: Int) {
            val lang = block.language.ifBlank { null }
            val highlighted = SyntaxHighlighter.highlight(block.text, lang)
            textView.text = highlighted
            textView.movementMethod = android.text.method.ScrollingMovementMethod()
            val desc = if (lang != null) "代码块，语言：$lang" else "代码块"
            itemView.contentDescription = desc
            itemView.setOnClickListener { onItemTap(block, index) }
            setupAccessibilityClick(itemView, block, index)
        }
    }

    inner class SeparatorViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        fun bind(index: Int) {
            itemView.contentDescription = "分隔线"
            itemView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
    }
}
