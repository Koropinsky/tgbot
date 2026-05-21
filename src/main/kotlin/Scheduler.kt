import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.ParseMode
import kotlinx.coroutines.*
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlin.time.Duration.Companion.hours

fun startScheduler(bot: Bot) {
    // 🌅 Ранкова розсилка о 7:00
    CoroutineScope(Dispatchers.Default).launch {
        val zoneId = ZoneId.of("Europe/Kyiv")
        while (isActive) {
            val now = ZonedDateTime.now(zoneId)
            var next7AM = now.withHour(7).withMinute(0).withSecond(0).withNano(0)
            if (now.isAfter(next7AM)) next7AM = next7AM.plusDays(1)
            delay(ChronoUnit.MILLIS.between(now, next7AM))

            val weather = getPoltavaWeather()
            val todayEvents = getTodayCalendarEvents()
            val eventsSection = if (todayEvents.isEmpty()) {
                "📅 *Сьогодні планів у календарі немає, чудовий день для відпочинку!*"
            } else {
                "📅 *Сьогоднішні плани:*\n" + todayEvents.joinToString("\n") { "• $it" }
            }
            val aiMessage = generateAiMessage(true)

            val fullMorningMessage = "🌅 *Доброго ранку!*\n\n$weather\n\n$eventsSection\n\n$aiMessage"

            getAllChatIds().forEach { id ->
                try {
                    bot.sendMessage(ChatId.fromId(id), text = fullMorningMessage, parseMode = ParseMode.MARKDOWN)
                } catch (e: Exception) {
                    println("Error sending morning message to $id: ${e.message}")
                }
            }
        }
    }

    // 💌 Денна мотивація кожні 4 години в активний час (7:00 - 22:00)
    CoroutineScope(Dispatchers.Default).launch {
        while (isActive) {
            delay(4.hours)
            val currentHour = ZonedDateTime.now(ZoneId.of("Europe/Kyiv")).hour
            if (currentHour in 7..22) {
                getAllChatIds().forEach { id ->
                    try {
                        bot.sendMessage(ChatId.fromId(id), text = "💌 ${generateAiMessage(false)}")
                    } catch (e: Exception) {
                        println("Error sending daytime message to $id: ${e.message}")
                    }
                }
            }
        }
    }

    // 🌅 Цитата дня о 10:00
    CoroutineScope(Dispatchers.Default).launch {
        val zoneId = ZoneId.of("Europe/Kyiv")
        while (isActive) {
            val now = ZonedDateTime.now(zoneId)
            var next10AM = now.withHour(10).withMinute(0).withSecond(0).withNano(0)
            if (now.isAfter(next10AM)) next10AM = next10AM.plusDays(1)
            delay(ChronoUnit.MILLIS.between(now, next10AM))
            getAllChatIds().forEach { id ->
                try {
                    bot.sendMessage(ChatId.fromId(id), text = "🌅 *Цитата дня:*\n\n${generateQuoteOfTheDay()}", parseMode = ParseMode.MARKDOWN)
                } catch (e: Exception) {
                    println("Error sending quote to $id: ${e.message}")
                }
            }
        }
    }
}
