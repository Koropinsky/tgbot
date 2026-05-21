import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.dispatcher.*
import com.github.kotlintelegrambot.entities.*
import com.github.kotlintelegrambot.entities.keyboard.*
import kotlinx.coroutines.*
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlin.time.Duration.Companion.hours

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    HttpServer.create(InetSocketAddress(port), 0).apply {
        createContext("/") { ex -> val res = "Bot is running!".toByteArray(); ex.sendResponseHeaders(200, res.size.toLong()); ex.responseBody.use { it.write(res) } }
        start()
    }
    initDatabase()

    val bot = bot {
        token = System.getenv("BOT_TOKEN") ?: "ТВІЙ_ТОКЕН"
        dispatch {
            command("start") {
                val chatId = message.chat.id
                saveChatId(chatId)
                userStates[chatId] = BotState.NONE
                val keyboard = KeyboardReplyMarkup(
                    keyboard = listOf(
                        listOf(KeyboardButton("🛒 Список покупок"), KeyboardButton("🎯 Наші цілі")),
                        listOf(KeyboardButton("✨ Надихни нас"), KeyboardButton("🍿 Порекомендуй щось"))
                    ),
                    resizeKeyboard = true
                )
                bot.sendMessage(ChatId.fromId(chatId), "Привіт! Я ваш сімейний бот. Все готово до роботи!", replyMarkup = keyboard)
            }
            text {
                val chatId = message.chat.id
                val state = userStates[chatId] ?: BotState.NONE
                val safeText = text.trim()

                when {
                    state == BotState.ADDING_SHOPPING -> {
                        if (safeText.isNotEmpty()) {
                            addShoppingItem(safeText)
                            userStates[chatId] = BotState.NONE
                            bot.sendMessage(ChatId.fromId(chatId), "✅ `$safeText` додано!")
                            showShoppingList(bot, chatId)
                        }
                    }
                    state == BotState.ADDING_GOAL -> {
                        if (safeText.isNotEmpty()) {
                            addGoalItem(safeText)
                            userStates[chatId] = BotState.NONE
                            bot.sendMessage(ChatId.fromId(chatId), "🎯 Ціль `$safeText` записана!")
                            showGoalsList(bot, chatId)
                        }
                    }

                    safeText == "🛒 Список покупок" -> showShoppingList(bot, chatId)
                    safeText == "🎯 Наші цілі" -> showGoalsList(bot, chatId)

                    safeText == "🍿 Порекомендуй щось" -> {
                        val inlineKeyboard = InlineKeyboardMarkup.create(
                            listOf(
                                InlineKeyboardButton.CallbackData("🎬 Фільм", "rec_movie"),
                                InlineKeyboardButton.CallbackData("📺 Серіал", "rec_series"),
                                InlineKeyboardButton.CallbackData("⛩️ Аніме", "rec_anime")
                            )
                        )
                        bot.sendMessage(ChatId.fromId(chatId), "Що будемо дивитися?", replyMarkup = inlineKeyboard)
                    }

                    safeText == "✨ Надихни нас" -> {
                        bot.sendMessage(ChatId.fromId(chatId), "⏳ Запитую ШІ...")
                        CoroutineScope(Dispatchers.IO).launch {
                            val aiMessage = generateAiMessage(isMorning = false)
                            getAllChatIds().forEach { id -> bot.sendMessage(ChatId.fromId(id), "💌 $aiMessage") }
                        }
                    }

                    safeText.startsWith("+") -> {
                        val item = safeText.removePrefix("+").trim()
                        if (item.isNotEmpty()) {
                            addShoppingItem(item)
                            bot.sendMessage(ChatId.fromId(chatId), "✅ `$item` додано до покупок!")
                        }
                    }
                    safeText.startsWith("*") -> {
                        val goal = safeText.removePrefix("*").trim()
                        if (goal.isNotEmpty()) {
                            addGoalItem(goal)
                            bot.sendMessage(ChatId.fromId(chatId), "🎯 `$goal` додано до цілей!")
                        }
                    }
                }
            }

            callbackQuery {
                val chatId = callbackQuery.message?.chat?.id ?: return@callbackQuery
                val messageId = callbackQuery.message?.messageId
                val data = callbackQuery.data

                when {
                    data == "start_add_shop" -> {
                        userStates[chatId] = BotState.ADDING_SHOPPING
                        bot.sendMessage(ChatId.fromId(chatId), "📝 Напиши назву покупки:")
                        bot.answerCallbackQuery(callbackQuery.id)
                    }
                    data == "start_add_goal" -> {
                        userStates[chatId] = BotState.ADDING_GOAL
                        bot.sendMessage(ChatId.fromId(chatId), "🚀 Напиши нову ціль:")
                        bot.answerCallbackQuery(callbackQuery.id)
                    }
                    data.startsWith("del_shop_") -> {
                        val id = data.removePrefix("del_shop_").toIntOrNull() ?: return@callbackQuery
                        deleteShoppingItem(id)
                        bot.answerCallbackQuery(callbackQuery.id, "Викреслено!")
                        if (messageId != null) updateShoppingMessage(bot, chatId, messageId)
                    }
                    data.startsWith("del_goal_") -> {
                        val id = data.removePrefix("del_goal_").toIntOrNull() ?: return@callbackQuery
                        deleteGoalItem(id)
                        bot.answerCallbackQuery(callbackQuery.id, "Вітаю з досягненням!")
                        if (messageId != null) updateGoalsMessage(bot, chatId, messageId)
                    }

                    // Запит на генерацію рекомендації
                    data.startsWith("rec_") -> {
                        val category = data.removePrefix("rec_")
                        bot.answerCallbackQuery(callbackQuery.id, "Генерую підбірку...")

                        CoroutineScope(Dispatchers.IO).launch {
                            val recommendation = generateRecommendation(category)

                            // Якщо це помилка API або фолбек, просто надсилаємо текст БЕЗ кнопок
                            if (recommendation.startsWith("Помилка API") || recommendation.startsWith("Внутрішня помилка") || recommendation.contains("Здається, я передивився")) {
                                bot.sendMessage(ChatId.fromId(chatId), "🍿 $recommendation")
                            } else {
                                val title = recommendation.lines().firstOrNull()?.replace(Regex("[*\"_]"), "")?.trim() ?: "Невідомий тайтл"

                                // Зберігаємо в оперативну пам'ять
                                lastRecommendations[chatId] = Pair(category, title)

                                val btn = InlineKeyboardMarkup.create(
                                    listOf(listOf(InlineKeyboardButton.CallbackData("👀 Вже дивились", "mark_watched")))
                                )
                                bot.sendMessage(ChatId.fromId(chatId), "🍿 $recommendation", replyMarkup = btn)
                            }
                        }
                    }

                    // Натиснули "Вже дивились" -> показуємо зірочки
                    data == "mark_watched" -> {
                        val rec = lastRecommendations[chatId]
                        if (rec != null) {
                            bot.answerCallbackQuery(callbackQuery.id)
                            val ratingKeyboard = InlineKeyboardMarkup.create(
                                listOf(
                                    listOf(
                                        InlineKeyboardButton.CallbackData("1⭐", "rate_1"),
                                        InlineKeyboardButton.CallbackData("2⭐", "rate_2"),
                                        InlineKeyboardButton.CallbackData("3⭐", "rate_3"),
                                        InlineKeyboardButton.CallbackData("4⭐", "rate_4"),
                                        InlineKeyboardButton.CallbackData("5⭐", "rate_5")
                                    )
                                )
                            )
                            if (messageId != null) {
                                bot.editMessageReplyMarkup(ChatId.fromId(chatId), messageId, replyMarkup = ratingKeyboard)
                                bot.sendMessage(ChatId.fromId(chatId), "Оцініть «${rec.second}» від 1 до 5:")
                            }
                        } else {
                            bot.answerCallbackQuery(callbackQuery.id, "Час очікування минув.", showAlert = true)
                        }
                    }

                    // Зберігаємо оцінку в БД
                    data.startsWith("rate_") -> {
                        val rating = data.removePrefix("rate_").toIntOrNull() ?: 0
                        val rec = lastRecommendations[chatId]

                        if (rec != null) {
                            addWatchedItem(rec.first, rec.second, rating)
                            bot.answerCallbackQuery(callbackQuery.id, "✅ Оцінка $rating⭐ збережена!")

                            if (messageId != null) {
                                bot.editMessageReplyMarkup(ChatId.fromId(chatId), messageId, replyMarkup = null)
                            }
                            bot.sendMessage(ChatId.fromId(chatId), "Запам'ятав! Ви оцінили «${rec.second}» на $rating⭐. ШІ врахує це наступного разу!")
                            lastRecommendations.remove(chatId)
                        } else {
                            bot.answerCallbackQuery(callbackQuery.id, "Помилка збереження.", showAlert = true)
                        }
                    }
                }
            }
        }
    }
    bot.startPolling()

    // Корутини для розсилок
    CoroutineScope(Dispatchers.Default).launch {
        val zoneId = ZoneId.of("Europe/Kyiv")
        while (isActive) {
            val now = ZonedDateTime.now(zoneId)
            var next7AM = now.withHour(7).withMinute(0).withSecond(0).withNano(0)
            if (now.isAfter(next7AM)) next7AM = next7AM.plusDays(1)
            delay(ChronoUnit.MILLIS.between(now, next7AM))
            getAllChatIds().forEach { id -> bot.sendMessage(ChatId.fromId(id), text = "🌅 *Доброго ранку!*\n\n${getPoltavaWeather()}\n\n${generateAiMessage(true)}", parseMode = ParseMode.MARKDOWN) }
        }
    }

    CoroutineScope(Dispatchers.Default).launch {
        while (isActive) {
            delay(4.hours)
            val currentHour = ZonedDateTime.now(ZoneId.of("Europe/Kyiv")).hour
            if (currentHour in 7..22) {
                getAllChatIds().forEach { id -> bot.sendMessage(ChatId.fromId(id), text = "💌 ${generateAiMessage(false)}") }
            }
        }
    }

    CoroutineScope(Dispatchers.Default).launch {
        val zoneId = ZoneId.of("Europe/Kyiv")
        while (isActive) {
            val now = ZonedDateTime.now(zoneId)
            var next10AM = now.withHour(10).withMinute(0).withSecond(0).withNano(0)
            if (now.isAfter(next10AM)) next10AM = next10AM.plusDays(1)
            delay(ChronoUnit.MILLIS.between(now, next10AM))
            getAllChatIds().forEach { id -> bot.sendMessage(ChatId.fromId(id), text = "🌅 *Цитата дня:*\n\n${generateQuoteOfTheDay()}", parseMode = ParseMode.MARKDOWN) }
        }
    }
}