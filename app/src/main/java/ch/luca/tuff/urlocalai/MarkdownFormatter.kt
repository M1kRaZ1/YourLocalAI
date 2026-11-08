package ch.luca.tuff.urlocalai

import android.graphics.Color
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import java.util.regex.Pattern

/**
 * Lightweight Markdown formatter converting basic Markdown syntax (bold, italic, code blocks,
 * inline code, headers, bullet points) into SpannableCharSequences for chat bubbles.
 */
object MarkdownFormatter {

    fun format(rawText: String): CharSequence {
        val builder = SpannableStringBuilder()
        val lines = rawText.split("\n")
        var inCodeBlock = false

        for ((index, line) in lines.withIndex()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("```")) {
                inCodeBlock = !inCodeBlock
                continue
            }

            val formattedLine = SpannableStringBuilder()
            if (inCodeBlock) {
                formattedLine.append(line)
                formattedLine.setSpan(TypefaceSpan("monospace"), 0, formattedLine.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                formattedLine.setSpan(BackgroundColorSpan(Color.parseColor("#2C2C2E")), 0, formattedLine.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                formattedLine.setSpan(ForegroundColorSpan(Color.parseColor("#34D399")), 0, formattedLine.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else {
                var parsedLine = line

                // Headers (# Header, ## Header)
                if (parsedLine.startsWith("# ")) {
                    formattedLine.append(parsedLine.substring(2))
                    formattedLine.setSpan(StyleSpan(Typeface.BOLD), 0, formattedLine.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    formattedLine.setSpan(RelativeSizeSpan(1.25f), 0, formattedLine.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                } else if (parsedLine.startsWith("## ")) {
                    formattedLine.append(parsedLine.substring(3))
                    formattedLine.setSpan(StyleSpan(Typeface.BOLD), 0, formattedLine.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    formattedLine.setSpan(RelativeSizeSpan(1.15f), 0, formattedLine.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                } else {
                    // Bullet points (- or *)
                    if (parsedLine.trimStart().startsWith("- ") || parsedLine.trimStart().startsWith("* ")) {
                        val indent = parsedLine.takeWhile { it == ' ' || it == '\t' }
                        formattedLine.append("$indent• ${parsedLine.trimStart().substring(2)}")
                    } else {
                        formattedLine.append(parsedLine)
                    }

                    applyInlineMarkdown(formattedLine)
                }
            }

            builder.append(formattedLine)
            if (index < lines.size - 1) {
                builder.append("\n")
            }
        }
        return builder
    }

    private fun applyInlineMarkdown(sb: SpannableStringBuilder) {
        val text = sb.toString()

        // Bold **text**
        val boldPattern = Pattern.compile("\\*\\*(.*?)\\*\\*")
        val boldMatcher = boldPattern.matcher(text)
        while (boldMatcher.find()) {
            val start = boldMatcher.start()
            val end = boldMatcher.end()
            if (start >= 0 && end <= sb.length) {
                sb.setSpan(StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

        // Italic *text*
        val italicPattern = Pattern.compile("\\*(.*?)\\*")
        val italicMatcher = italicPattern.matcher(text)
        while (italicMatcher.find()) {
            val start = italicMatcher.start()
            val end = italicMatcher.end()
            if (start >= 0 && end <= sb.length) {
                sb.setSpan(StyleSpan(Typeface.ITALIC), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

        // Inline code `code`
        val codePattern = Pattern.compile("`(.*?)`")
        val codeMatcher = codePattern.matcher(text)
        while (codeMatcher.find()) {
            val start = codeMatcher.start()
            val end = codeMatcher.end()
            if (start >= 0 && end <= sb.length) {
                sb.setSpan(TypefaceSpan("monospace"), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(BackgroundColorSpan(Color.parseColor("#2C2C2E")), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(ForegroundColorSpan(Color.parseColor("#34D399")), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }
}
