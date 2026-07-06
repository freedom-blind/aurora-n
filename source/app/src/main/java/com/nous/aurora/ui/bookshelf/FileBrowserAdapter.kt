package com.nous.aurora.ui.bookshelf

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.nous.aurora.R
import com.nous.aurora.util.AccessibilityUtil

class FileBrowserAdapter(
    private val onDirClick: (FileEntry) -> Unit,
    private val onFileClick: (FileEntry) -> Unit,
    private val onFileLongClick: ((FileEntry) -> Unit)? = null
) : RecyclerView.Adapter<FileBrowserAdapter.ViewHolder>() {

    private var entries: List<FileEntry> = emptyList()

    fun submitList(list: List<FileEntry>) {
        entries = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_file_entry, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(entries[position])
    }

    override fun getItemCount(): Int = entries.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val iconView: TextView = itemView.findViewById(R.id.tv_file_icon)
        private val nameView: TextView = itemView.findViewById(R.id.tv_file_name)

        fun bind(entry: FileEntry) {
            if (entry.isDirectory) {
                iconView.text = ""
                nameView.text = entry.name
                val desc = "文件夹，${entry.name}，点击进入"
                AccessibilityUtil.setAccessibilityFocusable(itemView, desc)
                itemView.setOnClickListener { onDirClick(entry) }
            } else {
                iconView.text = ""
                nameView.text = entry.name
                val desc = if (entry.isSupported) "电子书，${entry.name}，点击打开，长按收藏"
                    else "文件，${entry.name}，不支持此格式"
                AccessibilityUtil.setAccessibilityFocusable(itemView, desc)
                itemView.isEnabled = entry.isSupported
                itemView.alpha = if (entry.isSupported) 1.0f else 0.4f
                if (entry.isSupported) {
                    itemView.setOnClickListener { onFileClick(entry) }
                    itemView.setOnLongClickListener {
                        onFileLongClick?.invoke(entry)
                        true
                    }
                } else {
                    itemView.setOnClickListener(null)
                    itemView.setOnLongClickListener(null)
                }
            }
        }
    }
}