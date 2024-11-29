package io.github.beerpsi.tachiyomi.extension.ar.arabtoons

importio.github.beerpsi.tachiyomi.multisrc.magapoke.Magapoke
import java.text.SimpleDateFormat
import java.util.Locale

class ArabToons : magapoke(
    "عرب تونز",
    "https://arabtoons.net",
    "ar",
    dateFormat = SimpleDateFormat("MMM d", Locale("ar")),
) {
    override val useNewChapterEndpoint = true
}
