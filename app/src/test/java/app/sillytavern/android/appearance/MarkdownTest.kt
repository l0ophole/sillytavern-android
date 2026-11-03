package app.sillytavern.android.appearance

import org.junit.Assert.*
import org.junit.Test

class MarkdownTest {
    private fun spans(s: String, html: Boolean = true) = Markdown.inline(s, html)
    private fun para(s: String) = (Markdown.parse(s).single() as MdBlock.Paragraph).spans

    @Test fun plainText() = assertEquals(listOf(MdSpan("hello world")), spans("hello world"))
    @Test fun italicAsterisk() = assertEquals(listOf(MdSpan("a "), MdSpan("b", italic = true), MdSpan(" c")), spans("a *b* c"))
    @Test fun boldAsterisk() = assertEquals(listOf(MdSpan("b", bold = true)), spans("**b**"))
    @Test fun boldItalicTriple() = assertEquals(listOf(MdSpan("x", bold = true, italic = true)), spans("***x***"))
    @Test fun strike() = assertEquals(listOf(MdSpan("x", strike = true)), spans("~~x~~"))
    @Test fun inlineCodeIsLiteral() = assertEquals(listOf(MdSpan("*a*", code = true)), spans("`*a*`"))
    @Test fun snakeCaseUnderscoreNotItalic() = assertEquals(listOf(MdSpan("snake_case_name")), spans("snake_case_name"))
    @Test fun underscoreItalicAtWordBoundary() = assertEquals(listOf(MdSpan("x", italic = true)), spans("_x_"))
    @Test fun spaceAfterAsteriskIsLiteral() = assertEquals(listOf(MdSpan("2 * 3 * 4")), spans("2 * 3 * 4"))
    @Test fun unterminatedStarIsLiteral() = assertEquals(listOf(MdSpan("*waves")), spans("*waves"))
    @Test fun escapedStar() = assertEquals(listOf(MdSpan("*a*")), spans("\\*a\\*"))
    @Test fun link() = assertEquals(listOf(MdSpan("site", link = "https://x.org")), spans("[site](https://x.org)"))
    @Test fun quotedTextMarked() {
        assertEquals(listOf(MdSpan("He said "), MdSpan("\"", quote = true).copy(text = "\"hi\"", quote = true)), spans("He said \"hi\""))
    }
    @Test fun italicInsideQuote() {
        val s = spans("\"a *b* c\"")
        assertTrue(s.all { it.quote })
        assertTrue(s.any { it.italic && it.text == "b" })
    }
    @Test fun unterminatedQuoteLiteral() = assertEquals(listOf(MdSpan("say \"hi")), spans("say \"hi"))
    @Test fun htmlFormatting() = assertEquals(listOf(MdSpan("a", bold = true), MdSpan("b", italic = true, underline = true)), spans("<b>a</b><i><u>b</u></i>"))
    @Test fun htmlBreak() = assertEquals(listOf(MdSpan("a\nb")), spans("a<br>b"))
    @Test fun htmlUnknownTagStripped() = assertEquals(listOf(MdSpan("hi")), spans("<div class=\"x\">hi</div>"))
    @Test fun htmlScriptDropped() = assertEquals(listOf(MdSpan("ab")), spans("a<script>alert(1)</script>b"))
    @Test fun htmlDisabledShowsTags() = assertEquals(listOf(MdSpan("<b>a</b>")), spans("<b>a</b>", html = false))
    @Test fun htmlAnchorUnsafeSchemeNoLink() = assertEquals(listOf(MdSpan("x")), spans("<a href=\"javascript:alert(1)\">x</a>"))
    @Test fun htmlAnchorSafe() = assertEquals(listOf(MdSpan("x", link = "https://a.b")), spans("<a href=\"https://a.b\">x</a>"))
    @Test fun safeUrls() { assertTrue(Markdown.isSafeUrl("https://a")); assertFalse(Markdown.isSafeUrl("javascript:x")); assertFalse(Markdown.isSafeUrl("file:///etc/passwd")) }

    @Test fun singleNewlineStaysInParagraph() = assertEquals("a\nb", para("a\nb").joinToString("") { it.text })
    @Test fun blankLineSplitsParagraphs() = assertEquals(2, Markdown.parse("a\n\nb").size)
    @Test fun heading() { val b = Markdown.parse("## Title").single() as MdBlock.Heading; assertEquals(2, b.level); assertEquals("Title", b.spans.single().text) }
    @Test fun rule() = assertEquals(listOf(MdBlock.Rule), Markdown.parse("---"))
    @Test fun starRuleIsRuleNotList() = assertEquals(listOf(MdBlock.Rule), Markdown.parse("***"))
    @Test fun blockquote() { val q = Markdown.parse("> a\n> b").single() as MdBlock.Quote; assertEquals(1, q.blocks.size) }
    @Test fun bulletList() { val l = Markdown.parse("- a\n- b").single() as MdBlock.Items; assertFalse(l.ordered); assertEquals(2, l.items.size) }
    @Test fun orderedListKeepsStart() { val l = Markdown.parse("3. a\n4. b").single() as MdBlock.Items; assertTrue(l.ordered); assertEquals(3, l.start) }
    @Test fun asteriskActionIsNotList() = assertTrue(Markdown.parse("*waves*").single() is MdBlock.Paragraph)
    @Test fun fencedCode() { val c = Markdown.parse("```kotlin\nval a = *1*\n```").single() as MdBlock.Code; assertEquals("kotlin", c.lang); assertEquals("val a = *1*", c.text) }
    @Test fun unterminatedFenceRunsToEnd() { val c = Markdown.parse("```\nabc\ndef").single() as MdBlock.Code; assertEquals("abc\ndef", c.text) }
    @Test fun imageBlock() { val i = Markdown.parse("![cat](https://x/c.png)").single() as MdBlock.Image; assertEquals("https://x/c.png", i.url); assertEquals("cat", i.alt) }
    @Test fun mixedDocument() {
        val b = Markdown.parse("# T\ntext *a*\n\n- x\n\n```\nc\n```")
        assertEquals(listOf("Heading", "Paragraph", "Items", "Code"), b.map { it::class.simpleName })
    }
    @Test fun emptyInput() = assertTrue(Markdown.parse("").isEmpty())
    @Test fun crlfNormalised() = assertEquals(2, Markdown.parse("a\r\n\r\nb").size)
}
