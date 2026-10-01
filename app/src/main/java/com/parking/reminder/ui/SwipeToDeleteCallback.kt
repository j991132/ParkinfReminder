package com.parking.reminder.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.parking.reminder.R

/**
 * RecyclerView 항목 스와이프 삭제(Swipe-to-delete) 제스처 콜백
 */
abstract class SwipeToDeleteCallback(context: Context) :
    ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {

    private val deleteIcon = ContextCompat.getDrawable(context, R.drawable.ic_delete)
    private val background = ColorDrawable(Color.parseColor("#E53935")) // 삭제 레드 색상

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean = false // 드래그 앤 드롭 정렬은 미사용

    override fun onChildDraw(
        c: Canvas,
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        dX: Float,
        dY: Float,
        actionState: Int,
        isCurrentlyActive: Boolean
    ) {
        val itemView = viewHolder.itemView
        val iconMargin = (itemView.height - (deleteIcon?.intrinsicHeight ?: 0)) / 2

        if (dX > 0) { // 오른쪽으로 스와이프
            background.setBounds(itemView.left, itemView.top, itemView.left + dX.toInt(), itemView.bottom)
            deleteIcon?.let {
                val iconTop = itemView.top + iconMargin
                val iconBottom = iconTop + it.intrinsicHeight
                val iconLeft = itemView.left + iconMargin
                val iconRight = iconLeft + it.intrinsicWidth
                it.setBounds(iconLeft, iconTop, iconRight, iconBottom)
            }
        } else if (dX < 0) { // 왼쪽으로 스와이프
            background.setBounds(itemView.right + dX.toInt(), itemView.top, itemView.right, itemView.bottom)
            deleteIcon?.let {
                val iconTop = itemView.top + iconMargin
                val iconBottom = iconTop + it.intrinsicHeight
                val iconRight = itemView.right - iconMargin
                val iconLeft = iconRight - it.intrinsicWidth
                it.setBounds(iconLeft, iconTop, iconRight, iconBottom)
            }
        } else {
            background.setBounds(0, 0, 0, 0)
        }

        background.draw(c)
        deleteIcon?.setTint(Color.WHITE)
        deleteIcon?.draw(c)

        super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
    }
}
