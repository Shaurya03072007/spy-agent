package s.p.y.ui

import android.graphics.Color
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import s.p.y.R
import s.p.y.adb.LineType
import s.p.y.adb.TerminalLine
import s.p.y.databinding.ItemTerminalLineBinding

class TerminalAdapter : ListAdapter<TerminalLine, TerminalAdapter.LineViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LineViewHolder {
        val binding = ItemTerminalLineBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return LineViewHolder(binding)
    }

    override fun onBindViewHolder(holder: LineViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class LineViewHolder(private val binding: ItemTerminalLineBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(line: TerminalLine) {
            binding.tvLineContent.text = line.text

            val context = binding.root.context
            when (line.type) {
                LineType.PROMPT -> {
                    binding.tvLineContent.setTextColor(ContextCompat.getColor(context, R.color.text_terminal_prompt))
                    binding.tvLineContent.setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                }
                LineType.STDOUT -> {
                    binding.tvLineContent.setTextColor(ContextCompat.getColor(context, R.color.text_terminal_output))
                    binding.tvLineContent.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
                }
                LineType.STDERR, LineType.ERROR -> {
                    binding.tvLineContent.setTextColor(ContextCompat.getColor(context, R.color.text_terminal_error))
                    binding.tvLineContent.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
                }
                LineType.SUCCESS -> {
                    binding.tvLineContent.setTextColor(ContextCompat.getColor(context, R.color.text_terminal_success))
                    binding.tvLineContent.setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                }
                LineType.INFO -> {
                    binding.tvLineContent.setTextColor(ContextCompat.getColor(context, R.color.text_terminal_info))
                    binding.tvLineContent.setTypeface(Typeface.MONOSPACE, Typeface.ITALIC)
                }
            }
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<TerminalLine>() {
        override fun areItemsTheSame(oldItem: TerminalLine, newItem: TerminalLine): Boolean {
            return oldItem.timestamp == newItem.timestamp && oldItem.text == newItem.text
        }

        override fun areContentsTheSame(oldItem: TerminalLine, newItem: TerminalLine): Boolean {
            return oldItem == newItem
        }
    }
}
