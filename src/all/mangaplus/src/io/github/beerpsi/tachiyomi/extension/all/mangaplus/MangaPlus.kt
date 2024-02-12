package io.github.beerpsi.tachiyomi.extension.all.mangaplus

import android.app.Application
import android.os.Build
import android.text.InputType
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.lib.i18n.Intl
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.network.interceptor.rateLimitHost
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import io.github.beerpsi.tachiyomi.extension.all.mangaplus.models.ErrorAction
import io.github.beerpsi.tachiyomi.extension.all.mangaplus.models.MPAllTitlesViewV2
import io.github.beerpsi.tachiyomi.extension.all.mangaplus.models.MPHomeViewV3
import io.github.beerpsi.tachiyomi.extension.all.mangaplus.models.MPLanguage
import io.github.beerpsi.tachiyomi.extension.all.mangaplus.models.MPRegisterationData
import io.github.beerpsi.tachiyomi.extension.all.mangaplus.models.MPResponse
import io.github.beerpsi.tachiyomi.extension.all.mangaplus.models.MPSettingsViewV2
import io.github.beerpsi.tachiyomi.extension.all.mangaplus.models.MPTitle
import io.github.beerpsi.tachiyomi.extension.all.mangaplus.models.MPTitleDetailView
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.io.IOException
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.reflect.KProperty

private val API_URL = "https://jumpg-api.tokyo-cdn.com/api".toHttpUrl()

class MangaPlus(private val mpLang: MPLanguage) : HttpSource(), ConfigurableSource {

    override val name = "MANGA Plus by SHUEISHA"

    override val baseUrl = "https://mangaplus.shueisha.co.jp"

    override val lang = mpLang.lang

    override val supportsLatest = true

    override val client = network.client.newBuilder()
        .addInterceptor(::authIntercept)
        .addInterceptor(::thumbnailIntercept)
        .rateLimitHost(API_URL, 1)
        .build()

    override fun headersBuilder() = Headers.Builder()
        .set("User-Agent", "okhttp/4.9.0")

    private val internalLang = mpLang.internalLang

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val json: Json by injectLazy()

    private val intl = Intl(
        lang,
        setOf("en", "pt-BR", "vi"),
        "en",
        this::class.java.classLoader!!,
    )

    /**
     * Private cache to find the newest thumbnail URL in case the existing one
     * in Tachiyomi database is expired. It's also used during the chapter deeplink
     * handling to avoid an additional request if possible.
     */
    private var titleCache: Map<Int, MPTitle>? = null

    override fun fetchPopularManga(page: Int): Observable<MangasPage> {
        return if (page == 1) {
            client.newCall(popularMangaRequest(page))
                .asObservableSuccess()
                .map { popularMangaParse(it) }
        } else {
            Observable.just(parseDirectory(page))
        }
    }

    override fun popularMangaRequest(page: Int): Request {
        val url = API_URL.newBuilder()
            .addPathSegments("title_list/all_v2")
            .addCommonQueryParameters()
            .build()

        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val data = response.parseAsMpResponse<MPAllTitlesViewV2>()

        titleCache = data.allTitlesViewV2.allTitlesGroup
            .flatMap { it.titles }
            .filter { it.language == mpLang }
            .associateBy { it.titleId }

        return parseDirectory(1)
    }

    private fun parseDirectory(page: Int): MangasPage {
        val directory = titleCache!!.values
        val manga = directory.drop((page - 1) * 24).take(24).map { it.toSManga() }
        val hasNextPage = (page + 1) * 24 < titleCache!!.size

        return MangasPage(manga, hasNextPage)
    }

    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> {
        return if (page == 1) {
            client.newCall(latestUpdatesRequest(page))
                .asObservableSuccess()
                .map { latestUpdatesParse(it) }
        } else {
            Observable.just(parseDirectory(page))
        }
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val url = API_URL.newBuilder()
            .addPathSegment("home_v4")
            .addQueryParameter("lang", internalLang)
            .addQueryParameter("clang", internalLang)
            .addCommonQueryParameters()
            .build()

        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val data = response.parseAsMpResponse<MPHomeViewV3>()

        subscriptionReading = data.homeViewV3.userSubscription.planType != "basic"
        titleCache = data.homeViewV3.groups
            .flatMap {
                it.titleGroups
                    .flatMap { g -> g.titles.map { t -> t.title } }
            }
            .filter { it.language == mpLang }
            .associateBy { it.titleId }

        return parseDirectory(1)
    }

    override fun fetchSearchManga(
        page: Int,
        query: String,
        filters: FilterList,
    ): Observable<MangasPage> {
        return if (page == 1) {
            if (query.startsWith(PREFIX_ID_SEARCH)) {
                val url = "#/titles/${query.removePrefix(PREFIX_ID_SEARCH)}"

                return client.newCall(mangaDetailsRequest(SManga.create().apply { this.url = url }))
                    .asObservableSuccess()
                    .map { MangasPage(listOf(mangaDetailsParse(it)), false) }
            } else if (query.startsWith(PREFIX_CHAPTER_ID_SEARCH)) {
                val url = "#/viewer/${query.removePrefix(PREFIX_CHAPTER_ID_SEARCH)}"

                return client.newCall(pageListRequest(SChapter.create().apply { this.url = url }))
                    .asObservableSuccess()
                    .map {
                        val data = it.parseAsMpResponse<JsonObject>()
                        val titleId = data["mangaViewer"]!!.jsonObject["titleId"]!!.jsonPrimitive.int
                        val title = titleCache?.get(titleId)?.toSManga() ?: run {
                            val mangaUrl = "#/titles/$titleId"

                            client.newCall(mangaDetailsRequest(SManga.create().apply { this.url = mangaUrl }))
                                .execute()
                                .let { r -> mangaDetailsParse(r) }
                        }

                        MangasPage(listOf(title), false)
                    }
            }

            client.newCall(searchMangaRequest(page, query, filters))
                .asObservableSuccess()
                .map { searchMangaParse(it, query, filters) }
        } else {
            Observable.just(parseDirectory(page))
        }
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList) =
        popularMangaRequest(page)

    override fun searchMangaParse(response: Response) = throw UnsupportedOperationException()

    private fun searchMangaParse(response: Response, query: String, filters: FilterList): MangasPage {
        val data = response.parseAsMpResponse<MPAllTitlesViewV2>()

        titleCache = data.allTitlesViewV2.allTitlesGroup
            .flatMap {
                it.titles.filter { t -> t.language == mpLang }
            }
            .filter {
                it.name.contains(query, true)
            }
            .associateBy { it.titleId }

        return parseDirectory(1)
    }

    override fun getMangaUrl(manga: SManga) = baseUrl + manga.url.substring(1)

    override fun mangaDetailsRequest(manga: SManga): Request {
        val url = API_URL.newBuilder()
            .addPathSegment("title_detailV3")
            .addQueryParameter("title_id", manga.url.substringAfterLast("/"))
            .addQueryParameter("lang", internalLang)
            .addCommonQueryParameters()
            .build()

        return GET(url, headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val titleDetailView = response
            .parseAsMpResponse<MPTitleDetailView>()
            .titleDetailView

        if (titleDetailView.title.language != mpLang) {
            throw Exception(intl["not_available"])
        }

        subscriptionReading = titleDetailView.userSubscription.planType != "basic"

        return titleDetailView.toSManga(intl)
    }

    override fun chapterListRequest(manga: SManga) = mangaDetailsRequest(manga)

    override fun chapterListParse(response: Response): List<SChapter> {
        val data = response
            .parseAsMpResponse<MPTitleDetailView>()
            .titleDetailView
        val hidePaidChapters = preferences.getBoolean(PREF_HIDE_PAID_CHAPTERS, false)

        return if (hidePaidChapters && data.titleLabels.planType == "deluxe" && data.userSubscription.planType != "deluxe") {
            data.chapterListGroup
                .flatMap { it.firstChapterList + it.lastChapterList }
                .map { it.toSChapter() }
        } else {
            data.chapterList.map { it.toSChapter() }
        }.reversed()
    }

    override fun getChapterUrl(chapter: SChapter) = baseUrl + chapter.url.substring(1)

    private var subscriptionReading by object {
        private var inner: Boolean? = null

        operator fun setValue(thisRef: MangaPlus, property: KProperty<*>, value: Boolean) {
            if (inner == null) {
                inner = value
            }
        }

        operator fun getValue(thisRef: MangaPlus, property: KProperty<*>): Boolean {
            if (inner == null) {
                val url = API_URL.newBuilder()
                    .addPathSegment("settings_v2")
                    .addQueryParameter("lang", internalLang)
                    .addQueryParameter("viewer_mode", "horizontal")
                    .addQueryParameter("clang", internalLang)
                    .addCommonQueryParameters()
                    .build()

                val data = client.newCall(GET(url, headers)).execute().parseAsMpResponse<MPSettingsViewV2>()

                inner = data.settingsViewV2.userSubscription.planType != "basic"
            }

            return inner!!
        }
    }

    override fun pageListRequest(chapter: SChapter): Request {
        val url = API_URL.newBuilder()
            .addPathSegment("manga_viewer")
            .addQueryParameter("chapter_id", chapter.url.substringAfterLast("/"))
            .addQueryParameter(
                "split",
                if (preferences.getBoolean("${PREF_SPLIT_DOUBLE_PAGES}_$lang", false)) {
                    "yes"
                } else {
                    "no"
                },
            )
            .addQueryParameter(
                "img_quality",
                preferences.getString("${PREF_IMAGE_QUALITY}_$lang", "high")!!,
            )
            .addQueryParameter("ticket_reading", "no")
            .addQueryParameter("free_reading", if (subscriptionReading) "no" else "yes")
            .addQueryParameter("subscription_reading", if (subscriptionReading) "yes" else "no")
            .addQueryParameter("viewer_mode", "horizontal")
            .addCommonQueryParameters()
            .build()

        return GET(url, headers, CacheControl.FORCE_NETWORK)
    }

    override fun pageListParse(response: Response): List<Page> {
        val data = response.parseAsMpResponse<JsonObject>()

        return data["mangaViewer"]!!.jsonObject["pages"]!!.jsonArray
            .mapNotNull { it.jsonObject["mangaPage"] }
            .mapIndexed { i, it ->
                Page(i, imageUrl = it.jsonObject["imageUrl"]!!.jsonPrimitive.content)
            }
    }

    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = "${PREF_IMAGE_QUALITY}_$lang"
            title = intl["image_quality"]
            summary = "%s"
            entries = arrayOf(
                intl["image_quality_low"],
                intl["image_quality_medium"],
                intl["image_quality_high"],
            )
            entryValues = arrayOf("low", "high", "super_high")

            setDefaultValue("high")
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = "${PREF_SPLIT_DOUBLE_PAGES}_$lang"
            title = intl["split_double_pages"]
            summary = intl["split_double_pages_summary"]
            setDefaultValue(true)
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_HIDE_PAID_CHAPTERS
            title = intl["hide_paid_chapters"]
            summary = intl["hide_paid_chapters_summary"]
            setDefaultValue(false)
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_SECRET
            title = intl["access_token"]
            summary = intl["access_token_summary"]

            setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
        }.also(screen::addPreference)
    }

    private fun authIntercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        if (request.url.host != API_URL.host ||
            request.url.queryParameter("secret") != null ||
            request.url.pathSegments.last() == "register"
        ) {
            return chain.proceed(request)
        }

        val deviceToken = generateDeviceToken()
        val securityKey = calculateSecurityKey(deviceToken)
        val registerUrl = "$API_URL/register".toHttpUrl().newBuilder().apply {
            addQueryParameter("device_token", deviceToken)
            addQueryParameter("security_key", securityKey)
            addCommonQueryParameters()
        }.build()
        val registerRequest = Request.Builder()
            .method("PUT", ByteArray(0).toRequestBody())
            .url(registerUrl)
            .headers(headers)
            .build()
        val data = client
            .newCall(registerRequest)
            .execute()
            .parseAsMpResponse<MPRegisterationData>()
        val secret = data.registerationData.deviceSecret

        preferences
            .edit()
            .putString(PREF_SECRET, secret)
            .apply()

        val url = request.url.newBuilder()
            .addQueryParameter("secret", secret)
            .build()
        val newRequest = request.newBuilder().url(url).build()

        return chain.proceed(newRequest)
    }

    private fun thumbnailIntercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        // Check if it is 404 to maintain compatibility when the extension used Weserv.
        val isBadCode = (response.code == 401 || response.code == 404)

        if (!isBadCode && !request.url.toString().contains(TITLE_THUMBNAIL_PATH)) {
            return response
        }

        val titleId = request.url.toString()
            .substringBefore("/$TITLE_THUMBNAIL_PATH")
            .substringAfterLast("/")
            .toInt()
        val title = titleCache?.get(titleId) ?: return response

        response.close()
        val thumbnailRequest = GET(title.portraitImageUrl, request.headers)
        return chain.proceed(thumbnailRequest)
    }

    private inline fun <reified T> Response.parseAsMpResponse(): T {
        val data = json.decodeFromString<MPResponse<T>>(body.string())

        if (data.error != null) {
            if (data.error.action == ErrorAction.UNAUTHORIZED && request.url.pathSegments.last() == "manga_viewer") {
                throw Exception(intl["chapter_locked"])
            }

            val popup = data.error.popups.find { it.language == mpLang }
                ?: data.error.englishPopup

            if (popup.subject == "Not Found" && request.url.pathSegments.last() == "title_detailV3") {
                throw IOException(intl["title_removed"])
            }

            throw IOException("${popup.subject}: ${popup.body.ifEmpty { intl["unknown_error"] }}")
        }

        check(data.success != null) { intl["unknown_error"] }

        return data.success
    }

    private fun HttpUrl.Builder.addCommonQueryParameters() = apply {
        addQueryParameter("os", "android")
        addQueryParameter("os_ver", Build.VERSION.SDK_INT.toString())
        addQueryParameter("app_ver", APP_VER)
        addQueryParameter("format", "json")

        preferences.getString(PREF_SECRET, null)
            ?.takeIf { it.isNotBlank() }
            ?.let {
                addQueryParameter("secret", it)
            }
    }

    companion object {
        const val PREFIX_ID_SEARCH = "id:"
        const val PREFIX_CHAPTER_ID_SEARCH = "chapter-id:"
    }
}

private const val PREF_SECRET = "secret"
private const val PREF_IMAGE_QUALITY = "imageResolution"
private const val PREF_SPLIT_DOUBLE_PAGES = "splitImage"
private const val PREF_HIDE_PAID_CHAPTERS = "hidePaidChapters"

private const val APP_VER = "116"
private const val TITLE_THUMBNAIL_PATH = "title_thumbnail_portrait_list"

private fun ByteArray.toHex(): String = joinToString(separator = "") { eachByte -> "%02x".format(eachByte) }

fun generateDeviceToken() = Random.nextBytes(16).toHex()

fun calculateSecurityKey(deviceToken: String): String {
    val md5 = MessageDigest.getInstance("MD5")

    return md5.digest("${deviceToken}$securityKeySalt".encodeToByteArray()).toHex()
}
