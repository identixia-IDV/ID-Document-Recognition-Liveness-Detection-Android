package com.identixia.documentreader


import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.util.TypedValue
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import java.io.File


/**
 * One-scroll result: identity, fields, checks, image strip, Raw drawer.
 */
class ResultActivity : AppCompatActivity() {


    companion object {
        const val EXTRA_JSON = "result_json"
        const val EXTRA_JSON_FILE = "result_json_file"


        fun open(context: Context, json: String) {
            val file = File(context.cacheDir, "last_result.json")
            file.writeText(json)
            context.startActivity(
                Intent(context, ResultActivity::class.java)
                    .putExtra(EXTRA_JSON_FILE, file.absolutePath)
            )
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_result)


        val json = loadJson()


        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.title = getString(R.string.result_title)
        toolbar.setNavigationOnClickListener { finish() }


        val ident = ResultParser.identityLine(json)
        findViewById<TextView>(R.id.txtIdentTitle).text = ident.title
        findViewById<TextView>(R.id.txtIdentStatus).text = boldPrefix(ident.status)
        findViewById<TextView>(R.id.txtIdentCounts).text = ident.counts
        findViewById<TextView>(R.id.txtResult).text = ResultParser.pretty(json)


        val inflater = LayoutInflater.from(this)
        val overallContainer = findViewById<LinearLayout>(R.id.overallContainer)
        for (row in ResultParser.overallResults(json)) {
            val view = inflater.inflate(R.layout.item_overall_row, overallContainer, false)
            val kind = ResultParser.kindLabel(row.kind)
            view.findViewById<TextView>(R.id.txtOverallKind).text = kind
            val resultView = view.findViewById<TextView>(R.id.txtOverallResult)
            resultView.text = row.result
            resultView.setTextColor(
                getColor(
                    when (row.result) {
                        "pass" -> R.color.ix_accent
                        "fail" -> R.color.status_error
                        else -> R.color.ix_muted
                    }
                )
            )
            overallContainer.addView(view)
        }


        val container = findViewById<LinearLayout>(R.id.fieldsContainer)
        val fieldGroups = ResultParser.fieldGroups(json)
        if (fieldGroups.isEmpty()) {
            container.addView(mutedText("No fields in this response"))
        } else {
            for (group in fieldGroups) {
                addGroupTitle(container, ResultParser.sourceLabel(group.source), group.items.size)
                for (item in group.items) {
                    addFieldItem(container, item)
                }
            }
        }


        populateImages(findViewById(R.id.imagesContainer), ResultParser.images(json))


        val securityContainer = findViewById<LinearLayout>(R.id.securityContainer)
        val checkGroups = ResultParser.checkGroups(json)
        if (checkGroups.isEmpty()) {
            securityContainer.addView(mutedText(getString(R.string.security_empty)))
        } else {
            for (group in checkGroups) {
                addGroupTitle(securityContainer, ResultParser.kindLabel(group.kind), group.items.size)
                for (item in group.items) {
                    addCheckItem(securityContainer, item)
                }
            }
        }


        val raw = findViewById<TextView>(R.id.txtResult)
        findViewById<TextView>(R.id.btnRawToggle).setOnClickListener {
            raw.visibility = if (raw.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
    }


    private fun boldPrefix(text: String): SpannableString {
        val end = text.indexOf(" ·").let { if (it < 0) text.length else it }
        return SpannableString(text).apply {
            setSpan(StyleSpan(Typeface.BOLD), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun mutedText(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(getColor(R.color.ix_muted))
            textSize = 13f
        }
    }

    private fun addGroupTitle(container: LinearLayout, title: String, count: Int) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dip(10), 0, dip(6))
        }
        val pill = TextView(this).apply {
            text = title
            setTextColor(getColor(R.color.ix_accent))
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.06f
            setPadding(dip(10), dip(3), dip(10), dip(3))
            background = GradientDrawable().apply {
                setColor(0x240F766E)
                setStroke(dip(1), getColor(R.color.ix_accent))
                cornerRadius = 999f
            }
        }
        row.addView(pill, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        row.addView(TextView(this).apply {
            text = count.toString()
            setTextColor(getColor(R.color.ix_muted))
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dip(8), 0, 0, 0)
        })
        container.addView(row)
    }

    private fun addFieldItem(container: LinearLayout, item: FieldItem) {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dip(8), 0, dip(8))
        }
        col.addView(wrapText(TextView(this).apply {
            text = item.id
            setTextColor(getColor(R.color.ix_muted))
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }))
        col.addView(wrapText(TextView(this).apply {
            text = item.value
            setTextColor(getColor(R.color.ix_text))
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextIsSelectable(true)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }))
        if (item.score.isNotBlank()) {
            col.addView(wrapText(TextView(this).apply {
                text = item.score
                setTextColor(getColor(R.color.ix_muted))
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            }))
        }
        container.addView(col)
    }

    private fun addCheckItem(container: LinearLayout, item: CheckItem) {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dip(8), 0, dip(8))
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(wrapText(TextView(this).apply {
            text = item.id
            setTextColor(getColor(R.color.ix_text))
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }))
        row.addView(TextView(this).apply {
            text = item.result
            setTextColor(
                getColor(
                    when (item.result) {
                        "pass" -> R.color.ix_accent
                        "fail" -> R.color.status_error
                        else -> R.color.ix_muted
                    }
                )
            )
            textSize = 13f
        })
        col.addView(row)
        if (item.extra.isNotBlank()) {
            col.addView(wrapText(TextView(this).apply {
                text = item.extra
                setTextColor(getColor(R.color.ix_muted))
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            }))
        }
        container.addView(col)
    }

    private fun wrapText(tv: TextView): TextView {
        tv.isSingleLine = false
        tv.maxLines = Int.MAX_VALUE
        tv.ellipsize = null
        tv.setHorizontallyScrolling(false)
        return tv
    }

    private fun dip(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
        ).toInt()
    }

    private fun loadJson(): String {
        val path = intent.getStringExtra(EXTRA_JSON_FILE)
        if (!path.isNullOrEmpty()) {
            val file = File(path)
            if (file.isFile) return file.readText()
        }
        return intent.getStringExtra(EXTRA_JSON) ?: ""
    }


    private fun populateImages(container: LinearLayout, images: List<ResultImage>) {
        container.removeAllViews()
        if (images.isEmpty()) {
            container.addView(TextView(this).apply {
                text = getString(R.string.images_empty)
                setTextColor(getColor(R.color.ix_muted))
                textSize = 13f
            })
            return
        }
        val pad = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 8f, resources.displayMetrics
        ).toInt()
        val thumb = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 148f, resources.displayMetrics
        ).toInt()
        for (item in images) {
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(thumb, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = pad
                }
            }
            col.addView(ImageView(this).apply {
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageBitmap(item.bitmap)
                layoutParams = LinearLayout.LayoutParams(thumb, thumb)
            })
            col.addView(TextView(this).apply {
                text = item.category
                setTextColor(getColor(R.color.ix_muted))
                textSize = 12f
            })
            container.addView(col)
        }
    }
}
