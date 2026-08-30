package wiki.nplus.airadar.producers

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

object FeedParser {
    data class FeedItem(val id: String, val title: String, val link: String, val published: String)

    fun parse(xml: String): List<FeedItem> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            isNamespaceAware = true
        }
        val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray()))
        val atomEntries = doc.getElementsByTagNameNS("http://www.w3.org/2005/Atom", "entry")
        if (atomEntries.length > 0) {
            return (0 until atomEntries.length).mapNotNull { i -> atomEntry(atomEntries.item(i) as Element) }
        }
        val rssItems = doc.getElementsByTagName("item")
        return (0 until rssItems.length).mapNotNull { i -> rssItem(rssItems.item(i) as Element) }
    }

    private fun atomEntry(e: Element): FeedItem? {
        val title = text(e, "title") ?: return null
        val id = text(e, "id") ?: return null
        val links = e.getElementsByTagNameNS("http://www.w3.org/2005/Atom", "link")
        var link: String? = null
        for (i in 0 until links.length) {
            val l = links.item(i) as Element
            val rel = l.getAttribute("rel")
            if (rel.isEmpty() || rel == "alternate") link = l.getAttribute("href")
        }
        return FeedItem(
            id = id,
            title = title,
            link = link ?: id,
            published = text(e, "published") ?: text(e, "updated") ?: "",
        )
    }

    private fun rssItem(e: Element): FeedItem? {
        val title = text(e, "title") ?: return null
        val link = text(e, "link") ?: return null
        return FeedItem(
            id = text(e, "guid") ?: link,
            title = title,
            link = link,
            published = text(e, "pubDate") ?: "",
        )
    }

    private fun text(e: Element, tag: String): String? {
        val nodes = e.getElementsByTagName(tag)
        for (i in 0 until nodes.length) {
            if (nodes.item(i).parentNode == e) return nodes.item(i).textContent.trim().takeIf { it.isNotEmpty() }
        }
        return null
    }
}
