package com.baize.ai.util

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.Filter

/**
 * 安全的 ArrayAdapter，修复 Long 转 Int 问题
 */
class SafeArrayAdapter<T>(
    context: Context,
    resource: Int,
    private val items: List<T>
) : ArrayAdapter<T>(context, resource, ArrayList(items)) {

    override fun getItemId(position: Int): Long {
        return position.toLong()
    }

    override fun getFilter(): Filter {
        return object : Filter() {
            override fun performFiltering(constraint: CharSequence?): FilterResults {
                return FilterResults().apply {
                    values = items
                    count = items.size
                }
            }

            override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                notifyDataSetChanged()
            }
        }
    }
}
