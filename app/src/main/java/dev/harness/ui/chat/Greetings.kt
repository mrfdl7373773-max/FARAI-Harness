package dev.harness.ui.chat

import dev.harness.ui.i18n.AppLanguage
import java.util.Calendar
import kotlin.random.Random

/**
 * Headline on an empty session. A different one each time, picked from lines that fit the hour
 * (plus a pool that fits any time), in the app's language. Written for FARAI, not borrowed.
 */
object Greetings {
    private enum class Part { MORNING, AFTERNOON, EVENING, NIGHT }

    private fun part(hour: Int) = when (hour) {
        in 4..10 -> Part.MORNING
        in 11..15 -> Part.AFTERNOON
        in 16..20 -> Part.EVENING
        else -> Part.NIGHT
    }

    private val en = mapOf(
        Part.MORNING to listOf(
            "Fresh day, clean repo.",
            "Morning. What ships today?",
            "Early start? I'm awake too.",
            "Sun's up. Let's build something.",
            "First commit of the day?",
            "Good morning. Where do we begin?",
            "Breakfast first, or bugs first?",
        ),
        Part.AFTERNOON to listOf(
            "Afternoon. What's on the bench?",
            "Midday push? I'm in.",
            "Lunch done. Let's get to it.",
            "What are we untangling today?",
            "Half the day left. Plenty of time.",
            "Pick up where you left off?",
        ),
        Part.EVENING to listOf(
            "Evening session. What's the plan?",
            "Wrapping up, or just warming up?",
            "One more feature before dinner?",
            "Golden hour for good code.",
            "Let's close a few tabs tonight.",
            "Long day? I'll do the heavy lifting.",
        ),
        Part.NIGHT to listOf(
            "Late night, quiet terminal.",
            "Still up? Let's make it count.",
            "Night owl mode: on.",
            "The best ideas show up after midnight.",
            "Just you, me, and a blinking cursor.",
            "Midnight build? Say the word.",
        ),
    )
    private val enAny = listOf(
        "What are we making today?",
        "Got an idea? Let's sketch it out.",
        "Your workspace is ready.",
        "Tell me the problem. I'll bring the tools.",
        "Something to fix, or something new?",
        "Big project or quick tweak?",
        "Where should the fox start digging?",
        "Hand me the hard part.",
        "Ready when you are.",
        "Let's turn that thought into code.",
        "What's bugging you? Literally.",
        "Point me at a folder.",
    )

    private val id = mapOf(
        Part.MORNING to listOf(
            "Pagi! Hari ini mau ngerjain apa?",
            "Hari baru, repo baru?",
            "Bangun pagi, langsung ngoding?",
            "Pagi cerah, ide segar. Gas?",
            "Commit pertama hari ini, yuk.",
            "Sarapan dulu atau bug dulu?",
            "Selamat pagi. Mulai dari mana?",
        ),
        Part.AFTERNOON to listOf(
            "Siang-siang gini enaknya bikin apa?",
            "Habis makan siang, lanjut kerja?",
            "Yang kemarin belum kelar, mau dilanjut?",
            "Siang. Ada yang perlu dibenerin?",
            "Masih setengah hari. Santai, cukup kok.",
            "Lagi ngulik apa siang ini?",
        ),
        Part.EVENING to listOf(
            "Sore-sore, waktunya beresin satu fitur.",
            "Udah sore. Mau ditutup atau baru mulai?",
            "Satu fitur lagi sebelum makan malam?",
            "Senja datang, kode jalan.",
            "Capek seharian? Biar aku yang angkat beratnya.",
            "Malam ini kita beresin apa?",
        ),
        Part.NIGHT to listOf(
            "Belum tidur? Sekalian bikin sesuatu.",
            "Malam sunyi, terminal menyala.",
            "Mode begadang: aktif.",
            "Ide terbaik suka muncul tengah malam.",
            "Cuma kamu, aku, dan kursor yang kedip-kedip.",
            "Build tengah malam? Tinggal bilang.",
        ),
    )
    private val idAny = listOf(
        "Lagi kepikiran bikin apa?",
        "Punya ide? Yuk kita wujudkan.",
        "Workspace udah siap, tinggal mulai.",
        "Ceritain masalahnya, aku bawa alatnya.",
        "Mau benerin yang lama atau bikin yang baru?",
        "Proyek besar atau cuma tweak kecil?",
        "Rubahnya mau mulai gali dari mana?",
        "Kasih aku bagian yang paling susah.",
        "Siap kapan pun kamu siap.",
        "Ubah ide di kepala jadi kode, yuk.",
        "Ada bug yang bikin kesel?",
        "Tunjukin foldernya, sisanya urusanku.",
    )

    fun pick(lang: AppLanguage, now: Calendar = Calendar.getInstance(), random: Random = Random.Default): String {
        val p = part(now.get(Calendar.HOUR_OF_DAY))
        val pool = if (lang == AppLanguage.INDONESIAN) id.getValue(p) + idAny else en.getValue(p) + enAny
        return pool[random.nextInt(pool.size)]
    }
}
