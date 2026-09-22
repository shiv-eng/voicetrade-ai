package com.quietstack.voicetrade.core.i18n

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import java.util.Locale

/** The language of the app's screens: English or Hindi. Chosen once in onboarding, changeable in Settings. */
object AppLocale {
    private const val PREFS = "app_locale"
    private const val KEY = "lang"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun chosen(context: Context): Boolean = prefs(context).contains(KEY)

    /** What the user picked, or the phone's own language until they do. */
    fun effective(context: Context): String =
        prefs(context).getString(KEY, null) ?: if (Locale.getDefault().language == "hi") "hi" else "en"

    fun set(context: Context, lang: String) {
        prefs(context).edit().putString(KEY, lang).commit()
    }

    /** Wraps an activity's base context so every resource lookup uses the chosen language. */
    fun wrap(base: Context): Context {
        val locale = Locale.forLanguageTag(effective(base))
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }
}

/** Translate a fixed English phrase into Hindi when the app is in Hindi; anything unknown stays as written. */
@Composable
@ReadOnlyComposable
fun tr(en: String): String {
    val hindi = LocalConfiguration.current.locales[0].language == "hi"
    return if (hindi) HiStrings[en] ?: en else en
}

val HiStrings: Map<String, String> = mapOf(
    "IPOs" to "आईपीओ", "IPO" to "आईपीओ",
    "See what is open now, coming soon and just listed" to "देखें अभी क्या खुला है, क्या जल्द आ रहा है और क्या अभी लिस्ट हुआ",
    "Top movers" to "आज के बड़े मूवर्स", "Gainers" to "बढ़त वाले", "Losers" to "गिरावट वाले", "Price alerts" to "प्राइस अलर्ट",
    "Details" to "विवरण", "Full details" to "पूरा विवरण", "In the news" to "ख़बरों में",
    "IPOs · India" to "आईपीओ · भारत", "IPOs · United States" to "आईपीओ · अमेरिका", "See all IPOs" to "सभी आईपीओ देखें",
    "No IPOs to show right now." to "अभी दिखाने के लिए कोई आईपीओ नहीं है।",
    "Offer date" to "ऑफ़र की तारीख़", "Offer price" to "ऑफ़र प्राइस", "Lot size" to "लॉट साइज़", "Min. investment" to "न्यूनतम निवेश",
    "Subscribed" to "सब्सक्रिप्शन", "expected" to "अनुमानित", "Total subscription" to "कुल सब्सक्रिप्शन",
    "Live" to "लाइव", "Upcoming" to "जल्द आ रहा", "Closed" to "बंद", "Listed" to "लिस्टेड", "Priced" to "प्राइस्ड",
    "Open now" to "अभी खुले", "Coming soon" to "जल्द आ रहे", "Closed, awaiting listing" to "बंद, लिस्टिंग का इंतज़ार",
    "Recently listed" to "हाल में लिस्ट हुए", "Recently priced" to "हाल में प्राइस हुए",
    "Opens" to "खुलता है", "Closes" to "बंद होता है", "Allotment" to "अलॉटमेंट", "Listing" to "लिस्टिंग",
    "India" to "भारत", "US" to "अमेरिका", "Refresh" to "रिफ्रेश",
    "Timeline" to "समय-सीमा", "Subscription" to "सब्सक्रिप्शन", "Issue details" to "इश्यू का विवरण", "Documents" to "दस्तावेज़",
    "Mainboard IPO" to "मेनबोर्ड आईपीओ", "SME IPO" to "एसएमई आईपीओ", "Mainboard" to "मेनबोर्ड",
    "Information published by the exchange. Dates for allotment and listing are estimates. This is not a recommendation to apply. Grey market premium is unofficial, so it isn't shown." to
        "जानकारी एक्सचेंज द्वारा प्रकाशित है। अलॉटमेंट और लिस्टिंग की तारीखें अनुमान हैं। यह आवेदन करने की सलाह नहीं है। ग्रे मार्केट प्रीमियम अनौपचारिक है, इसलिए नहीं दिखाया गया।",
    "Buy" to "खरीदें", "Sell" to "बेचें", "Watch" to "वॉच करें", "Set a price alert" to "प्राइस अलर्ट लगाएँ",
    "Ask Mira about this stock" to "इस शेयर के बारे में मीरा से पूछें", "About the company" to "कंपनी के बारे में",
    "Chart isn't available right now." to "चार्ट अभी उपलब्ध नहीं है।", "Price alert" to "प्राइस अलर्ट", "Price" to "प्राइस",
    "Set alert" to "अलर्ट लगाएँ", "Cancel" to "रद्द करें",
    "Account value" to "अकाउंट का मूल्य", "Loading…" to "लोड हो रहा है…",
    "Your value history appears here after your first trade." to "आपकी पहली ट्रेड के बाद यहाँ अकाउंट की वैल्यू का इतिहास दिखेगा।",
    "Since your first trade" to "आपकी पहली ट्रेड के बाद से", "Allocation" to "आवंटन", "Other holdings" to "अन्य होल्डिंग्स", "Cash" to "कैश",
    "Waiting" to "इंतज़ार में", "Fired recently" to "हाल में बजे", "Remove alert" to "अलर्ट हटाएँ",
    "No alerts yet. Say \"tell me when Reliance crosses 1,300\", or tap the bell on any stock page." to
        "अभी कोई अलर्ट नहीं है। बोलें \"रिलायंस 1,300 पार करे तो बताना\", या किसी शेयर पेज पर घंटी दबाएँ।",
    "Morning market briefing" to "सुबह की मार्केट ब्रीफिंग",
    "A notification at 8:30 am with Nifty, Sensex, your holdings and IPOs today. Tap it to hear Mira read it." to
        "सुबह 8:30 बजे निफ्टी, सेंसेक्स, आपकी होल्डिंग्स और आज के आईपीओ की नोटिफ़िकेशन। टैप करके मीरा से सुनें।",
    "Above " to "ऊपर ", "Below " to "नीचे ",
)
