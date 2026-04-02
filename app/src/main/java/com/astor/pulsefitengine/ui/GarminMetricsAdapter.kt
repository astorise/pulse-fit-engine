package com.astor.pulsefitengine.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.astor.pulsefitengine.databinding.ItemMetricBinding

class GarminMetricsAdapter :
    ListAdapter<MetricCardUiModel, GarminMetricsAdapter.MetricViewHolder>(MetricDiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MetricViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding = ItemMetricBinding.inflate(inflater, parent, false)
        return MetricViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MetricViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class MetricViewHolder(
        private val binding: ItemMetricBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: MetricCardUiModel) {
            binding.metricTitleTextView.text = item.title
            binding.metricValueTextView.text = item.value
            binding.metricMetaTextView.text = "${item.wireName} (#${item.wireId}) • ${item.updatedAt}"
        }
    }

    private object MetricDiffCallback : DiffUtil.ItemCallback<MetricCardUiModel>() {
        override fun areItemsTheSame(oldItem: MetricCardUiModel, newItem: MetricCardUiModel): Boolean {
            return oldItem.wireId == newItem.wireId
        }

        override fun areContentsTheSame(oldItem: MetricCardUiModel, newItem: MetricCardUiModel): Boolean {
            return oldItem == newItem
        }
    }
}
