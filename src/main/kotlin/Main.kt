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
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.hours

enum class BotState { NONE, ADDING_SHOPPING, ADDING_GOAL }
val userStates = mutableMapOf<Long, BotState>()

// Словник для збереження останньої рекомендації (chatId -> (category, title))
val lastRecommendations = ConcurrentHashMap<Long, Pair<String, String>>()

fun getDatabaseConnection(): Connection {
    val rawUrl = System.getenv("DATABASE_URL") ?: return DriverManager.getConnection("jdbc:postgresql://localhost:5432/postgres")
    if (rawUrl.startsWith("postgres://") || rawUrl.startsWith("postgresql://")) {
        val uri = URI(rawUrl)
        val userInfo = uri.userInfo?.split(":")
        val user = userInfo?.getOrNull(0) ?: ""
        val password = userInfo?.getOrNull(1) ?: ""
        val host = uri.host
        val port = if (uri.port != -1) uri.port else 5432
        val path = uri.path
        val jdbcUrl = "jdbc:postgresql://$host:$port$path?sslmode=require&user=$user&password=$password"
        return DriverManager.getConnection(jdbcUrl)
    }
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
                CREATE TABLE IF NOT EXISTS watched_list (id SERIAL PRIMARY KEY, category TEXT NOT NULL, title TEXT NOT NULL);
            """.trimIndent())
        }
    } catch (e: Exception) { println("Database init error: ${e.message}") }
}

fun getWatchedList(category: String): List<String> = mutableListOf<String>().apply {
    getDatabaseConnection().use { conn ->
        val rs = conn.prepareStatement("SELECT title FROM watched_list WHERE category = ?").apply { setString(1, category) }.executeQuery()
        while (rs.next()) add(rs.getString("title"))
    }
}

fun addWatchedItem(category: String, title: String) = getDatabaseConnection().use { conn ->
    conn.prepareStatement("INSERT INTO watched_list (category, title) VALUES (?, ?)").apply {
        setString(1, category)
        setString(2, title)
        executeUpdate()
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

                    // Обробка запитів на рекомендацію
                    data.startsWith("rec_") -> {
                        val category = data.removePrefix("rec_")
                        bot.answerCallbackQuery(callbackQuery.id, "Генерую...")

                        CoroutineScope(Dispatchers.IO).launch {
                            val recommendation = generateRecommendation(category)
                            // ШІ поверне назву на найпершому рядку. Беремо її.
                            val title = recommendation.lines().firstOrNull()?.replace(Regex("[*\"_]"), "")?.trim() ?: "Невідомий тайтл"

                            // Зберігаємо в пам'ять для кнопки "Вже дивились"
                            lastRecommendations[chatId] = Pair(category, title)

                            val btn = InlineKeyboardMarkup.create(
                                listOf(listOf(InlineKeyboardButton.CallbackData("👀 Вже дивились", "mark_watched")))
                            )
                            bot.sendMessage(ChatId.fromId(chatId), "🍿 $recommendation", replyMarkup = btn)
                        }
                    }

                    // Обробка кнопки "Вже дивились"
                    data == "mark_watched" -> {
                        val rec = lastRecommendations[chatId]
                        if (rec != null) {
                            addWatchedItem(rec.first, rec.second)
                            bot.answerCallbackQuery(callbackQuery.id, "✅ Додано до переглянутого!")

                            // Прибираємо кнопку
                            if (messageId != null) {
                                bot.editMessageReplyMarkup(ChatId.fromId(chatId), messageId, replyMarkup = null)
                            }
                            bot.sendMessage(ChatId.fromId(chatId), "Запам'ятав! Більше не буду радити «${rec.second}».")
                            lastRecommendations.remove(chatId) // Чистимо пам'ять
                        } else {
                            bot.answerCallbackQuery(callbackQuery.id, "Час очікування минув або помилка.", showAlert = true)
                        }
                    }
                }
            }
        }
    }
    bot.startPolling()

    // 1. Ранкова розсилка о 7:00
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

    // 2. Денна розсилка кожні 4 години (7:00-22:00)
    CoroutineScope(Dispatchers.Default).launch {
        while (isActive) {
            delay(4.hours)
            val currentHour = ZonedDateTime.now(ZoneId.of("Europe/Kyiv")).hour
            if (currentHour in 7..22) {
                getAllChatIds().forEach { id -> bot.sendMessage(ChatId.fromId(id), text = "💌 ${generateAiMessage(false)}") }
            }
        }
    }

    // 3. Цитата дня о 10:00
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

// === НОВА ФУНКЦІЯ ДЛЯ РЕКОМЕНДАЦІЙ ===
fun generateRecommendation(category: String): String {
    val watchedList = getWatchedList(category).joinToString(", ")
    val avoidContext = if (watchedList.isNotEmpty()) "Ми вже дивилися ці тайтли, тому НІЯК НЕ РЕКОМЕНДУЙ ЇХ: $watchedList." else ""

    val categoryContext = when (category) {
        "movie" -> "крутий фільм"
        "series" -> "цікавий серіал"
        "anime" -> "аніме (врахуй, що нам дуже подобається екшен і дарк-фентезі, як от Jujutsu Kaisen, Chainsaw Man, Berserk, Black Clover та Arcane, але запропонуй щось інше, що також захоплює)"
        else -> "фільм"
    }

    val prompt = "Порекомендуй один $categoryContext для вечірнього перегляду. $avoidContext " +
            "ВАЖЛИВО: Твоя відповідь має складатися з двох частин. " +
            "На САМОМУ ПЕРШОМУ РЯДКУ напиши ТІЛЬКИ назву оригіналу (без слів 'Назва', без лапок і вступів). " +
            "Починаючи з другого рядка напиши короткий і захоплюючий опис без спойлерів українською мовою."

    return fetchFromGemini(prompt, listOf("Здається, я передивився всі бази! Спробуй ще раз пізніше. 😅"))
}

fun getPoltavaWeather(): String {
    val apiKey = System.getenv("WEATHER_API_KEY") ?: return "Погода: API ключ не знайдено."
    val url = "https://api.openweathermap.org/data/2.5/weather?q=Poltava,ua&appid=$apiKey&units=metric&lang=uk"
    return try {
        val client = HttpClient.newHttpClient()
        val request = HttpRequest.newBuilder().uri(URI.create(url)).build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        val json = JsonParser.parseString(response.body()).asJsonObject
        val temp = json.getAsJsonObject("main").get("temp").asDouble.toInt()
        val desc = json.getAsJsonArray("weather").get(0).asJsonObject.get("description").asString
        "🌤 Погода в Полтаві: ${temp}°C, $desc."
    } catch (e: Exception) { "Не вдалося дізнатися погоду." }
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

fun fetchFromGemini(prompt: String, fallbackMessages: List<String>): String {
    val apiKey = System.getenv("GEMINI_API_KEY") ?: return fallbackMessages.random()
    val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent?key=$apiKey"
    val jsonBody = """{"contents":[{"parts":[{"text":"${prompt.replace("\"", "\\\"")} "}]}]}"""

    return try {
        val client = HttpClient.newHttpClient()
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() != 200) {
            return fallbackMessages.random()
        }

        val jsonObject = JsonParser.parseString(response.body()).asJsonObject
        val text = jsonObject.getAsJsonArray("candidates")
            .get(0).asJsonObject
            .getAsJsonObject("content")
            .getAsJsonArray("parts")
            .get(0).asJsonObject
            .get("text").asString

        text.trim()
    } catch (e: Exception) {
        fallbackMessages.random()
    }
}

fun generateAiMessage(isMorning: Boolean): String {
    val currentHour = ZonedDateTime.now(ZoneId.of("Europe/Kyiv")).hour
    val isEvening = currentHour >= 18

    val morningPrompts = listOf(
        "Напиши миле ранкове повідомлення для мене та моєї дівчини Насосика. Згадай, як чудово починати день разом, коли крапельна кавоварка вже заварила нам свіжу каву перед тим, як вона поїде в офіс, а я сяду за код.",
        "Напиши кумедне ранкове повідомлення про наших котів: як поважний старший британець і дрібне кошеня вже з самого ранку влаштували тигидик і заважають Насосику збиратися в офіс, а мені — відкривати ноутбук.",
        "Напиши мотивуюче ранкове привітання. Побажай мені продуктивного програмування з самого ранку, а Насосику — легкого та успішного початку дня в її офісі. Нехай день пройде на одному диханні.",
        "Напиши ранкове мотиваційне повідомлення, використовуючи метафору з аніме 'Магічна битва' (Jujutsu Kaisen). Натякни, що з самого ранку ми розгортаємо таку 'Територію', де офісна рутина Насосика та мої баги зникнуть перед нашою супер-командою.",
        "Напиши бадьоре ранкове привітання для пари айтішника та офісного працівника. Побажай Насосику швидкої дороги до офісу без заторів, а мені — щоб ранковий сод відразу компилювався.",
        "Напиши амбітне ранкове повідомлення: сьогодні новий день, щоб зробити наш PushUp ScrollDown ще кращим. Нехай Насосика надихає її ранок в офісі, а мене — мій код.",
        "Напиши ранкове повідомлення про те, як приємно, що в нас обох є свої цілі — у Насосика в офісі, у мене в коді, — але найголовніша мета — це наш спільний затишний вечір.",
        "Напиши ранковий прогноз: сьогоднішній день для Насосика пройде легко, як по маслу, а в моєму коді не буде жодного 'дедлоку'. Гарного старту!",
        "Напиши ранкове повідомлення з акцентом на котів: вони вже чекають на нас ввечері, а поки що бажають нам обом продуктивно розібратися зі своїми справами — офісними та розробницькими.",
        "Напиши мотивацію: кожен виконаний сьогодні таск в офісі чи коді — це ще один крок до нашого успішного релізу PushUp ScrollDown."
    )

    val daytimePrompts = listOf(
        "Напиши дуже ніжне повідомлення, щоб підтримати Насосика посеред її насиченого офісного дня. Нагадай, що вдома на неї чекають коти і хлопець-програміст, який її сильно любить і нудьгує.",
        "Напиши мотивуюче повідомлення про наш додаток PushUp ScrollDown. Побажай мені продуктивного програмування, а Насосику — швидкого вирішення офісних справ.",
        "Напиши веселе денне повідомлення в стилі 'Людини-бензопили' (Chainsaw Man). Щось про те, що Насосик на обідній перерві розкидала всі офісні таски, а я розрізав складні алгоритми з енергією Дендзі.",
        "Напиши повідомлення з вайбом 'Аркейну': ми в різних світах (офіс і кодинг), але разом створюємо Хекстек-магію нашого життя.",
        "Напиши короткий, добрий і життєвий жарт, який змусить Насосика посміхнутися на офісній перерві від звітів, а мене — відволіктися від монітора і розім'ятися.",
        "Напиши життєве повідомлення про те, як коти сумують вдома посеред дня: чекають, поки Насосик приїде з офісу, і лізуть на клавіатуру до мене, поки я намагаюся кодити.",
        "Напиши підтримку: офісні звіти Насосика та мої технічні завдання — це просто крок до нашої спільної мети. Ви супер!",
        "Напиши підтримку: навіть якщо сьогоднішній день здається нескінченним, пам'ятай — ми з Насосиком супергерої, які просто прикидаються звичайними людьми в офісі та за монітором.",
        "Напиши мотивуюче повідомлення: нехай сьогоднішній день принесе нам обом відчуття 'Done' по всіх завданнях. Ми цього варті!",
        "Напиши повідомлення про те, як важливо в робочому хаосі знайти хвилину, щоб випити смачної кави й згадати, що ми команда. Насосик, ти молодець, тримайся там!",
        "Напиши повідомлення з вайбом 'спокою посеред шторму': у Насосика в офісі — дедлайни, у мене — баги, але вдома — спокій, коти й наш спільний світ."
    )

    val eveningPrompts = listOf(
        "Напиши затишне вечірнє повідомлення. Робочий день закінчився, офіс і код позаду. Час насолодитися вечором разом.",
        "Напиши повідомлення про те, як класно бути вдома: коти, затишок, жодних нарад чи багів. Вечір належить тільки нам.",
        "Напиши ніжне повідомлення для Насосика: вечір — це час забути про робочий хаос, обійнятися і просто побути удвох.",
        "Напиши вечірнє привітання: ми вижили в цьому робочому дні! Насосик впоралася з офісом, я з кодом — тепер час для еспресо-тоніка.",
        "Напиши нагадування для нас, що після важкого робочого дня найкращий релакс — це заритися разом увечері в ковдру поруч із муркотливими котами.",
        "Напиши повідомлення: ввечері в Полтаві так гарно. Насосик, відпочивай від офісу, сьогодні ми плануємо лише затишок.",
        "Напиши про наші вечірні цінності: ніяких офісних новин, ніяких техзавдань. Лише ми, коти і спокій після довгого дня.",
        "Напиши ніжне повідомлення: ти моя зірка в цьому вечорі. Забудь про роботу, Насосику, ти вже вдома.",
        "Напиши повідомлення про те, що робота — це лише спосіб оплатити наш спільний затишний вечір. Ми це зробили!"
    )

    val selectedList = when {
        isMorning -> morningPrompts
        isEvening -> eveningPrompts
        else -> daytimePrompts
    }

    val selectedPrompt = selectedList.random()
    val finalPrompt = "$selectedPrompt ВАЖЛИВО: Напиши лише сам текст повідомлення українською мовою. Без лапок, без вступних слів і привітань."

    val fallbackMessages = if (isMorning) {
        listOf(
            "Доброго ранку, соні! ☕️ Кава готова, коти нагодовані. Насосику — легкого дня в офісі, а тобі — успішного коду!",
            "З новим ранком! 🌅 Нехай кава додасть енергії, баги самі злякаються, а офісні таски Насті вирішаться клацанням пальців!"
        )
    } else if (isEvening) {
        listOf(
            "Робочий день позаду! Котики чекають, кава гріється. Насолоджуйтесь вечором, ви найкраща команда! ❤️",
            "Час відкласти звіти і код. Сьогоднішній вечір тільки для вас двох і котів! 🐈"
        )
    } else {
        listOf(
            "Котики, ШІ зараз відпочиває, але ви все одно найкраща команда! Насосику, тримайся там в офісі, скоро вечір і відпочинок! ❤️",
            "Хвилинка мотивації: Насосик підкорює офіс, ти підкорюєш код, а коти підкорюють диван. Ви супер! 🐈"
        )
    }

    return fetchFromGemini(finalPrompt, fallbackMessages)
}

fun generateQuoteOfTheDay(): String {
    val prompt = "Знайди одну дуже потужну, глибоку і надихаючу цитату стоїка, підприємця або філософа. Напиши тільки текст цитати та ім'я автора українською мовою. Без жодних зайвих слів чи вступів."

    val fallbackMessages = listOf(
        "«Навіть найдовший шлях починається з першого кроку.» — Лао-цзи",
        "«Те, що нас не вбиває, робить нас сильнішими.» — Фрідріх Ніцше",
        "«Успіх — це здатність крокувати від однієї невдачі до іншої, не втрачаючи ентузіазму.» — Вінстон Черчилль"
    )

    return fetchFromGemini(prompt, fallbackMessages)
}