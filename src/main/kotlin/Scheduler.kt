import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.ParseMode
import kotlinx.coroutines.*
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

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
            val reminderMessage = "💡 Не забудьте перевірити свій список справ на сьогодні!"

            val fullMorningMessage = "🌅 *Доброго ранку!*\n\n$weather\n\n$eventsSection\n\n$reminderMessage"

            getAllChatIds().forEach { id ->
                try {
                    bot.sendMessage(ChatId.fromId(id), text = fullMorningMessage, parseMode = ParseMode.MARKDOWN)
                } catch (e: Exception) {
                    println("Error sending morning message to $id: ${e.message}")
                }
            }
        }
    }

    // 💌 Денне нагадування о 14:00
    CoroutineScope(Dispatchers.Default).launch {
        val zoneId = ZoneId.of("Europe/Kyiv")
        while (isActive) {
            val now = ZonedDateTime.now(zoneId)
            var next14 = now.withHour(14).withMinute(0).withSecond(0).withNano(0)
            if (now.isAfter(next14)) next14 = next14.plusDays(1)
            delay(ChronoUnit.MILLIS.between(now, next14))
            
            getAllChatIds().forEach { id ->
                try {
                    bot.sendMessage(ChatId.fromId(id), text = "⏰ Нагадування: пройдіться по списку справ. Якщо ще щось залишилося — саме час доробити!")
                } catch (e: Exception) {
                    println("Error sending daytime message to $id: ${e.message}")
                }
            }
        }
    }
}
