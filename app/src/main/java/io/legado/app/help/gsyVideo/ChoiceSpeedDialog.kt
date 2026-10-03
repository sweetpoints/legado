package io.legado.app.help.gsyVideo

import android.content.Context

class ChoiceSpeedDialog(context: Context) : VideoChoiceDialog(context, .3f) {
    private var data: List<Float> = emptyList()
    private var listener: OnListItemClickListener? = null

    interface OnListItemClickListener {
        fun onItemClick(value: Float)

        fun finishDialog()
    }

    fun initList(data: List<Float>, onItemClickListener: OnListItemClickListener) {
        this.data = data.toList()
        listener = onItemClickListener
        heading = "倍速"
        choices = this.data.map { "${it}X" }
    }

    override fun select(index: Int) {
        data.getOrNull(index)?.let { listener?.onItemClick(it) }
    }

    override fun finished() {
        listener?.finishDialog()
    }
}
