package org.koitharu.kotatsu.parsers.site.animebootstrap.fr


import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.site.animebootstrap.AnimeBootstrapParser
import org.koitharu.kotatsu.parsers.util.attrAsRelativeUrl
import org.koitharu.kotatsu.parsers.util.domain
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.host
import org.koitharu.kotatsu.parsers.util.mapChapters
import org.koitharu.kotatsu.parsers.util.mapNotNullToSet
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.removeSuffix
import org.koitharu.kotatsu.parsers.util.selectFirstOrThrow
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.toTitleCase
import org.koitharu.kotatsu.parsers.util.tryParse
import org.koitharu.kotatsu.parsers.util.urlEncoded
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale


@MangaSourceParser("PAPSCAN", "PapScan", "fr")
internal class PapScan(context: MangaLoaderContext) :
	AnimeBootstrapParser(context, MangaSource.PAPSCAN, "papscan.com") {

	override val sourceLocale: Locale = Locale.ENGLISH

	override val listUrl = "/liste-manga"

	override val selectState = "div.anime__details__widget li:contains(En cours)"
	override val selectTag = "div.anime__details__widget li:contains(Genre) a"

	override val selectChapter = "ul.chapters li"

	override val sortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.POPULARITY,
		SortOrder.ALPHABETICAL,
	)

	override suspend fun getListPage(
		page: Int,
		query: String?,
		tags: Set<MangaTag>?,
		sortOrder: SortOrder,
	): List<Manga> {
		val url = buildString {
			append("https://")
			append(domain)
			append("/filterList")
			append("?page=")
			append(page.toString())

			if (!query.isNullOrEmpty()) {
				append("&alpha=")
				append(query.urlEncoded())
			}

			if (!tags.isNullOrEmpty()) {
				append("&cat=")
				for (tag in tags) {
					append(tag.key)
				}
			}
			append("&sortBy=")
			when (sortOrder) {
				SortOrder.POPULARITY -> append("views")
				SortOrder.ALPHABETICAL -> append("name")
				else -> append("updated")
			}
		}
		val doc = webClient.httpGet(url).parseHtml()

		return doc.select("div.product__item").map { div ->
			val href = div.selectFirstOrThrow("h5 a").attrAsRelativeUrl("href")
			Manga(
				id = generateUid(href),
				url = href,
				publicUrl = href.toAbsoluteUrl(div.host ?: domain),
				coverUrl = div.selectFirstOrThrow("div.product__item__pic").attr("data-setbg").orEmpty(),
				title = div.selectFirstOrThrow("div.product__item__text h5").text().orEmpty(),
				altTitle = null,
				rating = RATING_UNKNOWN,
				tags = emptySet(),
				author = null,
				state = null,
				source = source,
				isNsfw = isNsfwSource,
			)
		}
	}

	override suspend fun getTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain$listUrl").parseHtml()
		return doc.select("a.category ").mapNotNullToSet { a ->
			val key = a.attr("href").substringAfterLast('=')
			val name = a.text()
			MangaTag(
				key = key,
				title = name,
				source = source,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
		val fullUrl = manga.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(fullUrl).parseHtml()

		val chaptersDeferred = async { getChapters(manga, doc) }

		val desc = doc.selectFirstOrThrow(selectDesc).html()

		val state = if (doc.select(selectState).isNullOrEmpty()) {
			MangaState.FINISHED
		} else {
			MangaState.ONGOING
		}

		manga.copy(
			tags = doc.body().select(selectTag).mapNotNullToSet { a ->
				MangaTag(
					key = a.attr("href").removeSuffix('/').substringAfterLast('/'),
					title = a.text().toTitleCase(),
					source = source,
				)
			},
			description = desc,
			state = state,
			chapters = chaptersDeferred.await(),
		)
	}

	override suspend fun getChapters(manga: Manga, doc: Document): List<MangaChapter> {
		val dateFormat = SimpleDateFormat(datePattern, sourceLocale)
		return doc.body().select(selectChapter).mapChapters(reversed = true) { i, li ->
			val href = li.selectFirstOrThrow("a").attr("href")
			val dateText = li.selectFirst("span.date-chapter-title-rtl")?.text()
			MangaChapter(
				id = generateUid(href),
				name = li.selectFirstOrThrow("span em").text(),
				number = i + 1,
				url = href,
				uploadDate = dateFormat.tryParse(dateText),
				source = source,
				scanlator = null,
				branch = null,
			)
		}
	}

}