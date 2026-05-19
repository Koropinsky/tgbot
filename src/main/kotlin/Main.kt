import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.dispatcher.*
import com.github.kotlintelegrambot.entities.*
import com.github.kotlintelegrambot.entities.keyboard.*
import kotlinx.coroutines.*
import kotlin.time.Duration.Companion.hours

// Списки тепер динамічні
val shoppingList = mutableListOf<String>()
val goalsList = mutableListOf<String>("Запустити PushUp ScrollDown", "З'їздити у відпустку")
val userChatIds = mutableSetOf<Long>() // Змінив на Set, щоб точно не було дублів

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val server = HttpServer.create(InetSocketAddress(port), 0)
    server.createContext("/") { exchange ->
        val response = "Bot is running!".toByteArray()
        exchange.sendResponseHeaders(200, response.size.toLong())
        exchange.responseBody.use { it.write(response) }
    }
    server.start()

    val bot = bot {
        token = System.getenv("BOT_TOKEN") ?: "ТВІЙ_ТОКЕН"

        dispatch {
            command("start") {
                val chatId = message.chat.id
                userChatIds.add(chatId)

                val keyboard = KeyboardReplyMarkup(
                    keyboard = listOf(
                        listOf(KeyboardButton("🛒 Список покупок"), KeyboardButton("🎯 Наші цілі"))
                    ),
                    resizeKeyboard = true
                )
                bot.sendMessage(
                    chatId = ChatId.fromId(chatId),
                    text = "Привіт! Я ваш сімейний бот.\n\n" +
                            "🍏 Щоб додати покупку, напиши: `+Товар`\n" +
                            "🏆 Щоб додати ціль, напиши: `*Ціль`",
                    replyMarkup = keyboard
                )
            }

            text {
                val chatId = ChatId.fromId(message.chat.id)
                when (text) {
                    "🛒 Список покупок" -> {
                        if (shoppingList.isEmpty()) {
                            bot.sendMessage(chatId, "Список порожній! Додай щось через `+Товар`")
                        } else {
                            // Генеруємо кнопки для кожного товару
                            val inlineButtons = shoppingList.mapIndexed { index, item ->
                                listOf(InlineKeyboardButton.CallbackData(text = "❌ $item", callbackData = "shop_$index"))
                            }
                            val inlineMarkup = InlineKeyboardMarkup.create(inlineButtons)
                            bot.sendMessage(chatId, "Натисни на товар, щоб викреслити його:", replyMarkup = inlineMarkup)
                        }
                    }
                    "🎯 Наші цілі" -> {
                        if (goalsList.isEmpty()) {
                            bot.sendMessage(chatId, "Цілей поки немає! Додай через `*Ціль`")
                        } else {
                            val inlineButtons = goalsList.mapIndexed { index, item ->
                                listOf(InlineKeyboardButton.CallbackData(text = "✅ $item", callbackData = "goal_$index"))
                            }
                            val inlineMarkup = InlineKeyboardMarkup.create(inlineButtons)
                            bot.sendMessage(chatId, "Наші цілі (натисни, коли виконано!):", replyMarkup = inlineMarkup)
                        }
                    }
                    else -> {
                        if (text.startsWith("+")) {
                            val item = text.removePrefix("+").trim()
                            if (item.isNotEmpty()) {
                                shoppingList.add(item)
                                bot.sendMessage(chatId, "✅ `$item` додано до покупок!")
                            }
                        } else if (text.startsWith("*")) {
                            val item = text.removePrefix("*").trim()
                            if (item.isNotEmpty()) {
                                goalsList.add(item)
                                bot.sendMessage(chatId, "🎯 `$item` додано до цілей!")
                            }
                        }
                    }
                }
            }

            // Обробка натискань на Inline-кнопки
            callbackQuery {
                val chatId = ChatId.fromId(callbackQuery.message?.chat?.id ?: return@callbackQuery)
                val messageId = callbackQuery.message?.messageId
                val data = callbackQuery.data

                if (data.startsWith("shop_")) {
                    val index = data.removePrefix("shop_").toIntOrNull()
                    if (index != null && index in shoppingList.indices) {
                        val removedItem = shoppingList.removeAt(index)

                        // Оновлюємо клавіатуру після видалення
                        if (shoppingList.isEmpty()) {
                            bot.editMessageText(chatId, messageId, text = "Все куплено! Ви супер 🎉")
                        } else {
                            val newButtons = shoppingList.mapIndexed { i, item ->
                                listOf(InlineKeyboardButton.CallbackData(text = "❌ $item", callbackData = "shop_$i"))
                            }
                            bot.editMessageReplyMarkup(chatId, messageId, replyMarkup = InlineKeyboardMarkup.create(newButtons))
                        }
                        // Спливаюче повідомлення зверху
                        bot.answerCallbackQuery(callbackQuery.id, "$removedItem викреслено!")
                    }
                } else if (data.startsWith("goal_")) {
                    val index = data.removePrefix("goal_").toIntOrNull()
                    if (index != null && index in goalsList.indices) {
                        val removedGoal = goalsList.removeAt(index)

                        if (goalsList.isEmpty()) {
                            bot.editMessageText(chatId, messageId, text = "Усі цілі досягнуто! Час ставити нові 🚀")
                        } else {
                            val newButtons = goalsList.mapIndexed { i, item ->
                                listOf(InlineKeyboardButton.CallbackData(text = "✅ $item", callbackData = "goal_$i"))
                            }
                            bot.editMessageReplyMarkup(chatId, messageId, replyMarkup = InlineKeyboardMarkup.create(newButtons))
                        }
                        bot.answerCallbackQuery(callbackQuery.id, "Ціль '$removedGoal' досягнуто! Вітаю!")
                    }
                }
            }
        }
    }

    bot.startPolling()

    CoroutineScope(Dispatchers.Default).launch {
        val messages = listOf(
            "Гарного дня! Ви все зможете 🚀",
            "Насосик, ти найкраща! ❤️",
            "Не забудьте перевірити список покупок ввечері!",
            "Час випити кави та зробити паузу ☕"
        )

        while (isActive) {
            delay(4.hours)
            if (userChatIds.isNotEmpty()) {
                val randomMessage = messages.random()
                userChatIds.forEach { id ->
                    bot.sendMessage(ChatId.fromId(id), text = randomMessage)
                }
            }
        }
    }
}