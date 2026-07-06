package com.nous.aurora.ui.reader

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nous.aurora.R

/**
 * 可复用的弹窗列表组件（基于 AlertDialog，比 BottomSheetDialog 更稳定）。
 * 用于显示目录（支持层级折叠/展开）、书签列表、批注列表、搜索结果等。
 *
 * 目录模式（setTocItems）：
 * - 所有节点点击都会触发跳转（onTocItemClick）
 * - 有子项的节点显示 [+]/[-] 图标
 * - 点击节点跳转，长按切换折叠/展开
 *
 * 平面列表模式（setItems）：
 * - 点击触发 onItemClick 回调
 */
class ReaderBottomSheet(
    private val activity: android.app.Activity,
    private val title: String
) {

    private val adapter = SheetAdapter()
    private var dialog: AlertDialog? = null

    /** 点击目录项时的回调，参数为 locationJson */
    var onTocItemClick: ((String) -> Unit)? = null

    /** 点击平面列表项时的回调，参数为索引 */
    var onItemClick: ((Int) -> Unit)? = null

    fun setItems(items: List<SheetItem>) {
        adapter.setFlatItems(items) { index -> onItemClick?.invoke(index) }
    }

    fun setTocItems(tocTree: List<TocTreeItem>) {
        adapter.setTocTree(tocTree) { locationJson ->
            onTocItemClick?.invoke(locationJson)
            dialog?.dismiss()
        }
    }

    fun show() {
        val builder = AlertDialog.Builder(activity)
        builder.setTitle(title)

        val recyclerView = RecyclerView(activity)
        recyclerView.layoutManager = LinearLayoutManager(activity)
        recyclerView.adapter = adapter

        builder.setView(recyclerView)
        builder.setNegativeButton("关闭", null)
        dialog = builder.show()
    }

    fun dismiss() {
        dialog?.dismiss()
        dialog = null
    }

    // ── 数据模型 ──

    data class SheetItem(
        val title: String,
        val subtitle: String? = null,
        val depth: Int = 0
    )

    data class TocTreeItem(
        val title: String,
        val locationJson: String,
        val depth: Int = 0,
        val children: List<TocTreeItem> = emptyList()
    )

    // ── Adapter ──

    private class SheetAdapter : RecyclerView.Adapter<SheetAdapter.ViewHolder>() {

        private var items: List<Any> = emptyList()
        private var onSimpleClick: ((Int) -> Unit)? = null
        private var onTocClick: ((String) -> Unit)? = null
        private var tocTree: List<TocTreeItem> = emptyList()
        private val collapsedSet = mutableSetOf<String>()

        fun setFlatItems(list: List<SheetItem>, click: ((Int) -> Unit)?) {
            onSimpleClick = click
            onTocClick = null
            items = list
            notifyDataSetChanged()
        }

        fun setTocTree(tree: List<TocTreeItem>, click: ((String) -> Unit)?) {
            tocTree = tree
            onTocClick = click
            onSimpleClick = null
            collapsedSet.clear()
            rebuildFlatList()
            notifyDataSetChanged()
        }

        private fun rebuildFlatList() {
            items = flattenTree(tocTree, "")
        }

        private fun flattenTree(nodes: List<TocTreeItem>, parentKey: String): List<Any> {
            val result = mutableListOf<Any>()
            for (node in nodes) {
                val key = "$parentKey/${node.title}"
                val hasChildren = node.children.isNotEmpty()
                val isCollapsed = key in collapsedSet

                result.add(node)

                if (hasChildren && !isCollapsed) {
                    result.addAll(flattenTree(node.children, key))
                }
            }
            return result
        }

        private fun toggleCollapse(node: TocTreeItem, parentKey: String) {
            val key = "$parentKey/${node.title}"
            if (key in collapsedSet) {
                collapsedSet.remove(key)
            } else {
                collapsedSet.add(key)
            }
            rebuildFlatList()
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_sheet_row, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            if (item is SheetItem) {
                holder.bindSimple(item, position)
            } else if (item is TocTreeItem) {
                holder.bindTocNode(item, "")
            }
        }

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val titleView: TextView = itemView.findViewById(R.id.tv_sheet_item_title)
            private val subtitleView: TextView = itemView.findViewById(R.id.tv_sheet_item_subtitle)

            fun bindSimple(simple: SheetItem, position: Int) {
                val density = itemView.resources.displayMetrics.density
                val paddingStart = ((simple.depth * 24 + 16) * density).toInt()
                itemView.setPadding(
                    paddingStart,
                    itemView.paddingTop,
                    itemView.paddingRight,
                    itemView.paddingBottom
                )

                titleView.text = simple.title
                if (simple.subtitle != null) {
                    subtitleView.text = simple.subtitle
                    subtitleView.visibility = View.VISIBLE
                } else {
                    subtitleView.visibility = View.GONE
                }

                itemView.contentDescription =
                    simple.subtitle?.let { "${simple.title}，$it" } ?: simple.title
                itemView.setOnClickListener {
                    onSimpleClick?.invoke(position)
                }
            }

            fun bindTocNode(node: TocTreeItem, parentKey: String) {
                val density = itemView.resources.displayMetrics.density
                val paddingStart = ((node.depth * 24 + 16) * density).toInt()
                itemView.setPadding(
                    paddingStart,
                    itemView.paddingTop,
                    itemView.paddingRight,
                    itemView.paddingBottom
                )

                val key = "$parentKey/${node.title}"
                val hasChildren = node.children.isNotEmpty()
                val isCollapsed = key in collapsedSet

                val iconText = when {
                    hasChildren && isCollapsed -> "[+] "
                    hasChildren && !isCollapsed -> "[-] "
                    else -> ""
                }
                titleView.text = "$iconText${node.title}"

                itemView.contentDescription = buildString {
                    append("${node.title}，点击跳转")
                    if (hasChildren) {
                        if (isCollapsed) append("，已折叠，长按展开子章节")
                        else append("，已展开，长按折叠子章节")
                    }
                }

                // 点击：跳转（回调中会 dismiss）
                itemView.setOnClickListener {
                    onTocClick?.invoke(node.locationJson)
                }

                // 长按：切换折叠/展开
                if (hasChildren) {
                    itemView.setOnLongClickListener {
                        toggleCollapse(node, parentKey)
                        true
                    }
                } else {
                    itemView.setOnLongClickListener(null)
                }

                subtitleView.visibility = View.GONE
            }
        }
    }
}