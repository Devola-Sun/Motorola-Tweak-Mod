package com.motolauncher.mod

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView

/**
 * One side of the status bar order editor. Rows can be dragged up/down; the
 * swap button moves an element to the other side.
 */
class StatusBarOrderAdapter(
    private val onMoveToOtherSide: (StatusBarElement) -> Unit
) : RecyclerView.Adapter<StatusBarOrderAdapter.Holder>() {

    private val items = mutableListOf<StatusBarElement>()
    private var dragHelper: ItemTouchHelper? = null

    init {
        setHasStableIds(true)
    }

    fun attachDragHelper(helper: ItemTouchHelper) {
        dragHelper = helper
    }

    fun submit(newItems: List<StatusBarElement>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun keys(): List<String> = items.map { it.key }

    fun move(from: Int, to: Int) {
        if (from !in items.indices || to !in items.indices) return
        val item = items.removeAt(from)
        items.add(to, item)
        notifyItemMoved(from, to)
    }

    fun remove(element: StatusBarElement): Boolean {
        val index = items.indexOf(element)
        if (index < 0) return false
        items.removeAt(index)
        notifyItemRemoved(index)
        return true
    }

    fun add(element: StatusBarElement) {
        if (items.contains(element)) return
        items.add(element)
        notifyItemInserted(items.lastIndex)
    }

    fun snapshot(): List<StatusBarElement> = items.toList()

    override fun getItemId(position: Int): Long = items[position].key.hashCode().toLong()

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_status_bar_element, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position])
    }

    inner class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val icon: ImageView = itemView.findViewById(R.id.image_status_element_icon)
        private val title: TextView = itemView.findViewById(R.id.text_status_element_title)
        private val moveButton: ImageButton = itemView.findViewById(R.id.button_move_status_element)
        private val dragHandle: ImageView = itemView.findViewById(R.id.image_drag_handle)

        init {
            dragHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    dragHelper?.startDrag(this)
                }
                false
            }
        }

        fun bind(element: StatusBarElement) {
            title.setText(element.titleRes)
            icon.setImageResource(element.iconRes)
            moveButton.contentDescription =
                itemView.context.getString(R.string.status_bar_order_move)
            moveButton.setOnClickListener { onMoveToOtherSide(element) }
        }
    }
}
