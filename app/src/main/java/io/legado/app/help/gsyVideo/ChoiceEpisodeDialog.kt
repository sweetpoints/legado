package io.legado.app.help.gsyVideo

import android.content.Context
import io.legado.app.data.entities.BookChapter

class ChoiceEpisodeDialog(context: Context) : VideoChoiceDialog(context, .4f) {
    private var listener: OnListItemClickListener? = null

    interface OnListItemClickListener {
        fun onItemClick(position: Int)

        fun finishDialog()
    }

    fun initList(
        data: List<BookChapter>,
        onItemClickListener: OnListItemClickListener,
        initialSelection: Int = -1,
    ) {
        listener = onItemClickListener
        choices = data.map { it.title }
        heading = "选集（${choices.size}）"
        this.initialSelection = initialSelection
    }

    override fun select(index: Int) {
        listener?.onItemClick(index)
    }

    override fun finished() {
        listener?.finishDialog()
    }
}
