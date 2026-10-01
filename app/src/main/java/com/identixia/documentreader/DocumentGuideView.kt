package com.identixia.documentreader

import android.content.Context
import android.graphics.RectF
import android.util.AttributeSet
import com.identixia.documentreader.kit.DocumentGuideView as KitDocumentGuideView

/** Product-facing guide view for the full Document Reader app. */
class DocumentGuideView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : KitDocumentGuideView(context, attrs) {
    companion object {
        fun passportGuideRect(w: Float, h: Float): RectF = KitDocumentGuideView.passportGuideRect(w, h)
    }
}
