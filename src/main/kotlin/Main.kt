import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.dispatcher.*
import com.github.kotlintelegrambot.entities.*
import com.github.kotlintelegrambot.entities.keyboard.*
import kotlinx.coroutines.*
import kotlin.time.Duration.Companion.hours

// In-memory бази даних для старту (пізніше можна замінити на Room/Exposed)
val shoppingList = mutableListOf<String>()
// Сюди додасте ваші реальні chat_id після першого повідомлення боту
val userChatIds = mutableListOf<Long>() 

fun main() {
    // 1. Мінімальний HTTP-сервер для Render (щоб бот не засинав)
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val server = HttpServer.create(InetSocketAddress(port), 0)
    server.createContext("/") { exchange ->
        val response = "Bot is running!".toByteArray()
        exchange.sendResponseHeaders(200, response.size.toLong())
        exchange.responseBody.use { it.write(response) }
    }
    server.start()
    println("HTTP Server started on port $port")

    // 2. Ініціалізація бота
    val bot = bot {
        token = System.getenv("BOT_TOKEN") ?: "ТВІЙ_ТОКЕН_ТУТ"

        dispatch {
            command("start") {
                val chatId = message.chat.id
                if (!userChatIds.contains(chatId)) userChatIds.add(chatId)

                val keyboard = ReplyKeyboardMarkup.create(
                    listOf(
                        listOf(KeyboardButton("🛒 Список покупок"), KeyboardButton("🎯 Наші цілі"))
                    ),
                    resizeKeyboard = true
                )
                bot.sendMessage(
                    chatId = ChatId.fromId(chatId),
                    text = "Привіт! Я ваш сімейний бот. Що будемо робити?",
                    replyMarkup = keyboard
                )
            }

            text {
                val chatId = ChatId.fromId(message.chat.id)
                when (text) {
                    "🛒 Список покупок" -> {
                        val responseText = if (shoppingList.isEmpty()) "Список порожній!" else "Покупки:\n" + shoppingList.joinToString("\n") { "- $it" }
                        bot.sendMessage(chatId = chatId, text = responseText)
                        bot.sendMessage(chatId = chatId, text = "Щоб додати товар, просто напиши його назву починаючи з плюса, наприклад: +Молоко")
                    }
                    "🎯 Наші цілі" -> {
                        bot.sendMessage(chatId = chatId, text = "Наші довгострокові цілі:\n1. Запустити і монетизувати свої додатки\n2. Регулярно відпочивати\n3. ...")
                    }
                    else -> {
                        if (text.startsWith("+")) {
                            val item = text.removePrefix("+").trim()
                            shoppingList.add(item)
                            bot.sendMessage(chatId = chatId, text = "✅ $item додано до списку!")
                        }
                    }
                }
            }
        }
    }
    
    bot.startPolling()
    println("Bot started polling...")

    // 3. Фонова розсилка (Coroutines)
    CoroutineScope(Dispatchers.Default).launch {
        val messages = listOf(
            "Гарного дня! Ви все зможете 🚀",
            "Насосик, ти найкраща! ❤️",
            "Не забудьте перевірити список покупок ввечері!",
            "Час випити кави та зробити паузу ☕"
        )
        
        while (isActive) {
            delay(4.hours) // Розсилка кожні 4 години (налаштуй як зручно)
            if (userChatIds.isNotEmpty()) {
                val randomMessage = messages.random()
                userChatIds.forEach { id ->
                    bot.sendMessage(ChatId.fromId(id), text = randomMessage)
                }
            }
        }
    }
}