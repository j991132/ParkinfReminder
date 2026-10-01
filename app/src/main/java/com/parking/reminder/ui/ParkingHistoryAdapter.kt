package com.parking.reminder.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.parking.reminder.databinding.ItemParkingHistoryBinding
import com.parking.reminder.model.ParkingLocation

/**
 * 주차 히스토리 목록 RecyclerView 어댑터
 */
class ParkingHistoryAdapter(
    private val onItemClick: (ParkingLocation) -> Unit,
    private val onDeleteClick: (ParkingLocation) -> Unit
) : ListAdapter<ParkingLocation, ParkingHistoryAdapter.HistoryViewHolder>(DiffCallback) {

    inner class HistoryViewHolder(
        private val binding: ItemParkingHistoryBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ParkingLocation) {
            binding.tvFloorBadge.text = item.shortFloor
            binding.tvPillarNumber.text = "${item.pillar} 기둥"
            binding.tvTimestamp.text = "${item.formattedTime} • ${item.registeredBy}"

            binding.root.setOnClickListener {
                onItemClick(item)
            }

            binding.btnDeleteHistory.setOnClickListener {
                onDeleteClick(item)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryViewHolder {
        val binding = ItemParkingHistoryBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return HistoryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    companion object {
        private val DiffCallback = object : DiffUtil.ItemCallback<ParkingLocation>() {
            override fun areItemsTheSame(oldItem: ParkingLocation, newItem: ParkingLocation): Boolean {
                return oldItem.id == newItem.id
            }

            override fun areContentsTheSame(oldItem: ParkingLocation, newItem: ParkingLocation): Boolean {
                return oldItem == newItem
            }
        }
    }
}
