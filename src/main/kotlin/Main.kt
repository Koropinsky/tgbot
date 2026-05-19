import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.dispatcher.*
import com.github.kotlintelegrambot.entities.*
import com.github.kotlintelegrambot.entities.keyboard.*
import kotlinx.coroutines.*
import java.sql.DriverManager
import java.sql.Connection
import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.time.Duration.Companion.hours

enum class BotState { NONE, ADDING_SHOPPING, ADDING_GOAL }
val userStates = mutableMapOf<Long, BotState>()

fun getDatabaseConnection(): Connection {
    val rawUrl = System.getenv("DATABASE_URL") ?: "jdbc:postgresql://localhost:5432/postgres"
    val dbUrl = if (rawUrl.startsWith("jdbc:")) rawUrl else "jdbc:$rawUrl"
    return DriverManager.getConnection(dbUrl)
}

fun initDatabase() {
    try {
        getDatabaseConnection().use { conn ->
            conn.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS shopping_list (id SERIAL PRIMARY KEY, item TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS goals_list (id SERIAL PRIMARY KEY, goal TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS user_chats (chat_id BIGINT PRIMARY KEY);
            """.trimIndent())
        }
        println("Database initialized successfully.")
    } catch (e: Exception) {
        println("Database init error: ${e.message}")
    }
}

fun getShoppingList(): List<Pair<Int, String>> = mutableListOf<Pair<Int, String>>().apply {
    getDatabaseConnection().use { conn ->
        val rs = conn.createStatement().executeQuery("SELECT id, item FROM shopping_list ORDER BY id")
        while (rs.next()) add(rs.getInt("id") to rs.getString("item"))
    }
}

fun addShoppingItem(item: String) = getDatabaseConnection().use { conn ->
    conn.prepareStatement("INSERT INTO shopping_list (item) VALUES (?)").apply { setString(1, item); executeUpdate() }
}

fun deleteShoppingItem(id: Int) = getDatabaseConnection().use { conn ->
    conn.prepareStatement("DELETE FROM shopping_list WHERE id = ?").apply { setInt(1, id); executeUpdate() }
}

fun getGoalsList(): List<Pair<Int, String>> = mutableListOf<Pair<Int, String>>().apply {
    getDatabaseConnection().use { conn ->
        val rs = conn.createStatement().executeQuery("SELECT id, goal FROM goals_list ORDER BY id")
        while (rs.next()) add(rs.getInt("id") to rs.getString("goal"))
    }
}

fun addGoalItem(goal: String) = getDatabaseConnection().use { conn ->
    conn.prepareStatement("INSERT INTO goals_list (goal) VALUES (?)").apply { setString(1, goal); executeUpdate() }
}

fun deleteGoalItem(id: Int) = getDatabaseConnection().use { conn ->
    conn.prepareStatement("DELETE FROM goals_list WHERE id = ?").apply { setInt(1, id); executeUpdate() }
}

fun saveChatId(chatId: Long) = getDatabaseConnection().use { conn ->
    conn.prepareStatement("INSERT INTO user_chats (chat_id) VALUES (?) ON CONFLICT DO NOTHING").apply { setLong(1, chatId); executeUpdate() }
}

fun getAllChatIds(): Set<Long> = mutableSetOf<Long>().apply {
    getDatabaseConnection().use { conn ->
        val rs = conn.createStatement().executeQuery("SELECT chat_id FROM user_chats")
        while (rs.next()) add(rs.getLong("chat_id"))
    }
}

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    HttpServer.create(InetSocketAddress(port), 0).apply {
        createContext("/") { ex ->
            val res = "Bot is running!".toByteArray()
            ex.sendResponseHeaders(200, res.size.toLong())
            ex.responseBody.use { it.write(res) }
        }
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
                    keyboard = listOf(listOf(KeyboardButton("🛒 Список покупок"), KeyboardButton("🎯 Наші цілі"))),
                    resizeKeyboard = true
                )
                bot.sendMessage(ChatId.fromId(chatId), "Привіт! Я ваш сімейний бот.\nМожна додавати кнопками, або швидко через текст:\n`+Молоко`\n`*Нова ціль`", replyMarkup = keyboard)
            }

            text {
                val chatId = message.chat.id
                val state = userStates[chatId] ?: BotState.NONE
                val safeText = text.trim()

                when {
                    // 1. Додавання через кнопки (попередньо натиснули "Додати")
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

                    // 2. Виклик меню
                    safeText == "🛒 Список покупок" -> showShoppingList(bot, chatId)
                    safeText == "🎯 Наші цілі" -> showGoalsList(bot, chatId)

                    // 3. Швидке додавання через текст (+ та *)
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
                        bot.sendMessage(ChatId.fromId(chatId), "📝 Напиши назву покупки, яку треба додати:")
                        bot.answerCallbackQuery(callbackQuery.id)
                    }
                    data == "start_add_goal" -> {
                        userStates[chatId] = BotState.ADDING_GOAL
                        bot.sendMessage(ChatId.fromId(chatId), "🚀 Напиши нову довгострокову ціль:")
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
                }
            }
        }
    }
    bot.startPolling()

    CoroutineScope(Dispatchers.Default).launch {
        while (isActive) {
            delay(4.hours) // Розсилка кожні 4 години
            val chatIds = try { getAllChatIds() } catch (e: Exception) { emptySet() }

            if (chatIds.isNotEmpty()) {
                // Звертаємося до ШІ за свіжим повідомленням
                val aiMessage = generateAiMessage()

                chatIds.forEach { id ->
                    bot.sendMessage(ChatId.fromId(id), text = aiMessage)
                }
            }
        }
    }
}

fun showShoppingList(bot: com.github.kotlintelegrambot.Bot, chatId: Long) {
    val list = getShoppingList()
    val buttons = list.map { (id, item) -> listOf(InlineKeyboardButton.CallbackData("❌ $item", "del_shop_$id")) }.toMutableList()
    buttons.add(listOf(InlineKeyboardButton.CallbackData("➕ Додати покупку", "start_add_shop")))
    bot.sendMessage(ChatId.fromId(chatId), if (list.isEmpty()) "Список покупок порожній." else "Ваш список покупок:", replyMarkup = InlineKeyboardMarkup.create(buttons))
}

fun showGoalsList(bot: com.github.kotlintelegrambot.Bot, chatId: Long) {
    val list = getGoalsList()
    val buttons = list.map { (id, goal) -> listOf(InlineKeyboardButton.CallbackData("✅ $goal", "del_goal_$id")) }.toMutableList()
    buttons.add(listOf(InlineKeyboardButton.CallbackData("➕ Додати ціль", "start_add_goal")))
    bot.sendMessage(ChatId.fromId(chatId), if (list.isEmpty()) "Цілей поки немає." else "Наші довгострокові цілі:", replyMarkup = InlineKeyboardMarkup.create(buttons))
}

fun updateShoppingMessage(bot: com.github.kotlintelegrambot.Bot, chatId: Long, messageId: Long) {
    val list = getShoppingList()
    if (list.isEmpty()) {
        bot.editMessageText(ChatId.fromId(chatId), messageId, text = "Все куплено! Ви супер 🎉")
        bot.editMessageReplyMarkup(ChatId.fromId(chatId), messageId, replyMarkup = InlineKeyboardMarkup.create(listOf(listOf(InlineKeyboardButton.CallbackData("➕ Додати покупку", "start_add_shop")))))
    } else {
        val buttons = list.map { (id, item) -> listOf(InlineKeyboardButton.CallbackData("❌ $item", "del_shop_$id")) }.toMutableList()
        buttons.add(listOf(InlineKeyboardButton.CallbackData("➕ Додати покупку", "start_add_shop")))
        bot.editMessageReplyMarkup(ChatId.fromId(chatId), messageId, replyMarkup = InlineKeyboardMarkup.create(buttons))
    }
}

fun updateGoalsMessage(bot: com.github.kotlintelegrambot.Bot, chatId: Long, messageId: Long) {
    val list = getGoalsList()
    if (list.isEmpty()) {
        bot.editMessageText(ChatId.fromId(chatId), messageId, text = "Усі цілі досягнуто! Час ставити нові 🚀")
        bot.editMessageReplyMarkup(ChatId.fromId(chatId), messageId, replyMarkup = InlineKeyboardMarkup.create(listOf(listOf(InlineKeyboardButton.CallbackData("➕ Додати ціль", "start_add_goal")))))
    } else {
        val buttons = list.map { (id, goal) -> listOf(InlineKeyboardButton.CallbackData("✅ $goal", "del_goal_$id")) }.toMutableList()
        buttons.add(listOf(InlineKeyboardButton.CallbackData("➕ Додати ціль", "start_add_goal")))
        bot.editMessageReplyMarkup(ChatId.fromId(chatId), messageId, replyMarkup = InlineKeyboardMarkup.create(buttons))
    }
}

fun generateAiMessage(): String {
    val apiKey = System.getenv("GEMINI_API_KEY") ?: return "Гарного дня! ❤️" // Фолбек, якщо ключа немає
    val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$apiKey"

    // Тут ти можеш налаштувати промпт як завгодно!
    val prompt = "Напиши одне коротке, миле та мотивуюче повідомлення для мене та моєї дівчини Насосика. Без привітань, одразу текст. Можна згадати смачну каву, котів або побажати успіхів з нашим додатком PushUp ScrollDown."

    val jsonBody = """{"contents":[{"parts":[{"text":"$prompt"}]}]}"""

    return try {
        val client = HttpClient.newHttpClient()
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        val jsonObject = JsonParser.parseString(response.body()).asJsonObject

        // Витягуємо сам текст з відповіді Gemini
        val text = jsonObject.getAsJsonArray("candidates")
            .get(0).asJsonObject
            .getAsJsonObject("content")
            .getAsJsonArray("parts")
            .get(0).asJsonObject
            .get("text").asString

        text.trim()
    } catch (e: Exception) {
        println("Помилка генерації: ${e.message}")
        "Котики, ви найкращі! Гарного дня 🚀" // Повідомлення на випадок, якщо API відвалиться
    }
}