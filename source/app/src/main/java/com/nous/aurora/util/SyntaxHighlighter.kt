package com.nous.aurora.util

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import java.util.regex.Pattern

object SyntaxHighlighter {

    private const val C_KEYWORD = 0xFF569CD6.toInt()
    private const val C_STRING = 0xFFCE9178.toInt()
    private const val C_COMMENT = 0xFF6A9955.toInt()
    private const val C_NUMBER = 0xFFB5CEA8.toInt()
    private const val C_FUNCTION = 0xFFDCDCAA.toInt()
    private const val C_TYPE = 0xFF4EC9B0.toInt()

    private data class Rule(val pattern: String, val color: Int)

    private val patterns = mapOf(
        "python" to listOf(
            Rule("""\b(def|class|return|if|elif|else|for|while|import|from|as|try|except|finally|with|yield|lambda|pass|break|continue|and|or|not|in|is|None|True|False|raise|async|await)\b""", C_KEYWORD),
            Rule("""("#.*"|'#.*')""", C_STRING),
            Rule("""("[^"]*"|'[^']*')""", C_STRING),
            Rule("""#[^\n]*""", C_COMMENT),
            Rule("""\b\d+\.?\d*\b""", C_NUMBER),
            Rule("""\bdef\s+(\w+)""", C_FUNCTION),
            Rule("""\b(int|float|str|bool|list|dict|tuple|set)\b""", C_TYPE),
        ),
        "javascript" to listOf(
            Rule("""\b(function|const|let|var|return|if|else|for|while|do|switch|case|break|continue|try|catch|finally|throw|new|class|extends|import|export|from|default|async|await|yield|this|super|true|false|null|undefined)\b""", C_KEYWORD),
            Rule("""("[^"]*"|'[^']*'|`[^`]*`)""", C_STRING),
            Rule("""//[^\n]*""", C_COMMENT),
            Rule("""/\*[\s\S]*?\*/""", C_COMMENT),
            Rule("""\b\d+\.?\d*\b""", C_NUMBER),
        ),
        "kotlin" to listOf(
            Rule("""\b(fun|val|var|class|object|interface|data|sealed|enum|when|if|else|for|while|do|return|try|catch|finally|throw|import|package|as|is|in|this|super|true|false|null|by|override|private|protected|internal|public|abstract|open|final|companion|const|suspend|inline)\b""", C_KEYWORD),
            Rule("""("[^"]*")""", C_STRING),
            Rule("""//[^\n]*""", C_COMMENT),
            Rule("""\b\d+\.?\d*[LfF]?\b""", C_NUMBER),
            Rule("""@\w+""", C_TYPE),
        ),
        "java" to listOf(
            Rule("""\b(public|private|protected|static|final|abstract|class|interface|extends|implements|new|return|if|else|for|while|do|switch|case|break|continue|try|catch|finally|throw|throws|import|package|void|int|long|double|float|boolean|char|byte|short|true|false|null|this|super)\b""", C_KEYWORD),
            Rule("""("[^"]*")""", C_STRING),
            Rule("""//[^\n]*""", C_COMMENT),
            Rule("""\b\d+\.?\d*[LfFdD]?\b""", C_NUMBER),
            Rule("""@\w+""", C_TYPE),
        ),
        "xml" to listOf(
            Rule("""</?[\w:.-]+[\s>]""", C_KEYWORD),
            Rule("""\w+="[^"]*"""", C_STRING),
            Rule("""<!--[\s\S]*?-->""", C_COMMENT),
        ),
        "json" to listOf(
            Rule("""("[^"]*")\s*:""", C_KEYWORD),
            Rule("""("[^"]*")""", C_STRING),
            Rule("""\b\d+\.?\d*\b""", C_NUMBER),
            Rule("""\b(true|false|null)\b""", C_TYPE),
        ),
        "bash" to listOf(
            Rule("""\b(if|then|else|elif|fi|for|while|do|done|case|esac|in|function|return|exit|export|local|echo|printf|cd|ls|cat|grep|sed|awk|curl|wget|mkdir|rm|cp|mv|chmod|sudo|apt|git|docker)\b""", C_KEYWORD),
            Rule("""("[^"]*"|'[^']*')""", C_STRING),
            Rule("""#[^\n]*""", C_COMMENT),
            Rule("""\$\{?\w+\}?""", C_FUNCTION),
        ),
        "yaml" to listOf(
            Rule("""^[\w-]+:""", C_KEYWORD),
            Rule("""("[^"]*"|'[^']*')""", C_STRING),
            Rule("""#[^\n]*""", C_COMMENT),
        ),
        "sql" to listOf(
            Rule("""\b(SELECT|FROM|WHERE|INSERT|INTO|VALUES|UPDATE|SET|DELETE|CREATE|TABLE|ALTER|DROP|INDEX|JOIN|LEFT|RIGHT|INNER|OUTER|ON|AND|OR|NOT|NULL|IS|LIKE|IN|BETWEEN|ORDER|BY|GROUP|HAVING|LIMIT|OFFSET|UNION|ALL|DISTINCT|AS|CASE|WHEN|THEN|ELSE|END|EXISTS|PRIMARY|KEY|FOREIGN|REFERENCES|COUNT|SUM|AVG|MAX|MIN)\b""", C_KEYWORD),
            Rule("""('[^']*')""", C_STRING),
            Rule("""--[^\n]*""", C_COMMENT),
            Rule("""\b\d+\.?\d*\b""", C_NUMBER),
        ),
    )

    fun highlight(code: String, language: String?): SpannableStringBuilder {
        val rules = language?.lowercase()?.let { patterns[it] } ?: emptyList()
        val sb = SpannableStringBuilder(code)
        if (rules.isEmpty()) return sb

        for (rule in rules) {
            val p = Pattern.compile(rule.pattern, Pattern.MULTILINE)
            val m = p.matcher(code)
            while (m.find()) {
                val g = if (m.groupCount() >= 1) 1 else 0
                val start = m.start(g)
                val end = m.end(g)
                if (start >= 0 && end <= code.length && start < end) {
                    sb.setSpan(ForegroundColorSpan(rule.color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
        return sb
    }
}
