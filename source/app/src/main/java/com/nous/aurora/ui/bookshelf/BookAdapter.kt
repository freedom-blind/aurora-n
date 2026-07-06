package com.nous.aurora.ui.bookshelf

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.nous.aurora.R
import com.nous.aurora.data.model.Book
import kotlin.math.abs

class BookAdapter(
    private val onBookClick: (Book) -> Unit,
    private val onBookLongClick: (Book) -> Unit
) : ListAdapter<Book, BookAdapter.BookViewHolder>(BookDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BookViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_book, parent, false)
        return BookViewHolder(view)
    }

    override fun onBindViewHolder(holder: BookViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class BookViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val coverView: ImageView = itemView.findViewById(R.id.iv_cover)
        private val titleView: TextView = itemView.findViewById(R.id.tv_title)
        private val authorView: TextView = itemView.findViewById(R.id.tv_author)
        private val progressView: TextView = itemView.findViewById(R.id.tv_progress)

        fun bind(book: Book) {
            titleView.text = book.title
            authorView.text = book.author.ifEmpty { "未知作者" }

            val progressText = if (book.totalParagraphs > 0) {
                val pct = (book.lastParagraphIndex * 100 / book.totalParagraphs).coerceIn(0, 100)
                "已读 $pct% · ${book.format.name}"
            } else {
                book.format.name
            }
            progressView.text = progressText

            // Load cover with Coil
            if (book.coverPath.isNotEmpty() && java.io.File(book.coverPath).exists()) {
                coverView.load(java.io.File(book.coverPath)) {
                    crossfade(true)
                    placeholder(android.R.drawable.ic_menu_gallery)
                    error(android.R.drawable.ic_menu_close_clear_cancel)
                }
            } else {
                // Show first letter placeholder
                coverView.setImageDrawable(null)
                coverView.setBackgroundColor(generateColor(book.title))
                coverView.contentDescription = book.title.firstOrNull()?.toString() ?: "?"
            }

            val desc = buildString {
                append("${book.title}，")
                append(if (book.author.isNotEmpty()) "作者${book.author}，" else "")
                append("格式${book.format.name}，")
                append("进度${progressText}")
                if (book.isFavorite) append("，已收藏")
            }
            itemView.contentDescription = desc
            itemView.setOnClickListener { onBookClick(book) }
            itemView.setOnLongClickListener { onBookLongClick(book); true }
        }
    }

    private fun generateColor(title: String): Int {
        val colors = intArrayOf(
            0xFFE57373.toInt(), 0xFF81C784.toInt(), 0xFF64B5F6.toInt(),
            0xFFFFB74D.toInt(), 0xFFBA68C8.toInt(), 0xFF4DB6AC.toInt(),
            0xFF7986CB.toInt(), 0xFFA1887F.toInt()
        )
        return colors[abs(title.hashCode()) % colors.size]
    }

    class BookDiffCallback : DiffUtil.ItemCallback<Book>() {
        override fun areItemsTheSame(oldItem: Book, newItem: Book): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: Book, newItem: Book): Boolean =
            oldItem == newItem
    }
}
