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
import kotlin.time.Duration.Companion.hours

enum class BotState { NONE, ADDING_SHOPPING, ADDING_GOAL }
val userStates = mutableMapOf<Long, BotState>()

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
                    keyboard = listOf(
                        listOf(KeyboardButton("🛒 Список покупок"), KeyboardButton("🎯 Наші цілі")),
                        listOf(KeyboardButton("✨ Надихни нас"))
                    ),
                    resizeKeyboard = true
                )
                bot.sendMessage(ChatId.fromId(chatId), "Привіт! Я ваш сімейний бот.\nМожна додавати кнопками, або швидко через текст:\n`+Молоко`\n`*Нова ціль`", replyMarkup = keyboard)
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

                    safeText == "✨ Надихни нас" -> {
                        bot.sendMessage(ChatId.fromId(chatId), "⏳ Запитую ШІ...")

                        CoroutineScope(Dispatchers.IO).launch {
                            val aiMessage = generateAiMessage()
                            val allChatIds = try { getAllChatIds() } catch (e: Exception) { setOf(chatId) }

                            allChatIds.forEach { id ->
                                bot.sendMessage(ChatId.fromId(id), "💌 $aiMessage")
                            }
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

    // 1. Автоматична розсилка (кожні 3 години)
    CoroutineScope(Dispatchers.Default).launch {
        while (isActive) {
            delay(3.hours)
            val chatIds = try { getAllChatIds() } catch (e: Exception) { emptySet() }

            if (chatIds.isNotEmpty()) {
                val aiMessage = generateAiMessage()
                chatIds.forEach { id ->
                    bot.sendMessage(ChatId.fromId(id), text = "💌 $aiMessage")
                }
            }
        }
    }

    // 2. Щоденна "Цитата дня" о 10:00 (за Київським часом)
    CoroutineScope(Dispatchers.Default).launch {
        val zoneId = ZoneId.of("Europe/Kyiv") // Наш часовий пояс

        while (isActive) {
            val now = ZonedDateTime.now(zoneId)
            var next10AM = now.withHour(10).withMinute(0).withSecond(0).withNano(0)

            // Якщо зараз вже після 10:00, плануємо на завтра
            if (now.isAfter(next10AM) || now.isEqual(next10AM)) {
                next10AM = next10AM.plusDays(1)
            }

            // Рахуємо, скільки мілісекунд залишилося до 10:00 і спимо
            val delayMillis = ChronoUnit.MILLIS.between(now, next10AM)
            delay(delayMillis)

            val chatIds = try { getAllChatIds() } catch (e: Exception) { emptySet() }
            if (chatIds.isNotEmpty()) {
                val quote = generateQuoteOfTheDay()
                chatIds.forEach { id ->
                    // Використовуємо Markdown (зірочки) для виділення жирним
                    bot.sendMessage(ChatId.fromId(id), text = "🌅 *Цитата дня:*\n\n$quote", parseMode = ParseMode.MARKDOWN)
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

// === УНІВЕРСАЛЬНА ФУНКЦІЯ ДЛЯ ЗАПИТІВ ДО ШІ ===
fun fetchFromGemini(prompt: String, fallbackMessages: List<String>): String {
    val apiKey = System.getenv("GEMINI_API_KEY") ?: return fallbackMessages.random()
    val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent?key=$apiKey"

    // Щоб уникнути помилок з лапками в тексті промпта, використовуємо Gson для створення JSON
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

// 1. Генерація звичайних мотивуючих повідомлень
fun generateAiMessage(): String {
    val prompts = listOf(
        "Напиши миле ранкове повідомлення для мене та моєї дівчини Насосика. Згадай, як приємно прокидатися, коли крапельна кавоварка вже готує ранкову каву.",
        "Напиши смішне повідомлення для нас про те, як наш поважний старший кіт-британець і мале кошеня знову влаштовують метушню, але ми їх все одно обожнюємо.",
        "Напиши мотивуюче коротке повідомлення про наш додаток PushUp ScrollDown. Побажай нам успіхів і натхнення в розробці.",
        "Напиши мотиваційне повідомлення, використовуючи круту метафору з аніме 'Магічна битва' (Jujutsu Kaisen) про розширення території нашого успіху.",
        "Напиши дуже ніжне і коротке повідомлення для моєї дівчини Насосика. Скажи, що ми з нею найкраща команда.",
        "Напиши надихаюче повідомлення, використовуючи відому філософську цитату про наполегливість або успіх. Додай побажання крутого дня.",
        "Напиши повідомлення з вайбом теплого вечора. Згадай, як класно випити освіжаючий еспресо-тонік і просто відпочити удвох після кодінгу.",
        "Напиши веселе і трохи шалене повідомлення в стилі 'Людини-бензопили' (Chainsaw Man). Наприклад, що ми розірвемо всі проблеми як Дендзі.",
        "Напиши повідомлення для пари айтішників. Побажай, щоб код компілювався з першого разу, баги обходили стороною, а релізи були успішними.",
        "Напиши красиве повідомлення для нас з Насосиком, використовуючи стилістику або настрій серіалу 'Аркейн' (Arcane). Щось про те, що ми разом створюємо магію.",
        "Напиши нагадування для нас, що іноді найкраща продуктивність — це просто повалятися вдома з котами і дозволити собі нічого не робити.",
        "Напиши повідомлення-побажання з відсилкою на аніме 'Пекельний рай' (Hell's Paradise). Про те, що разом ми пройдемо будь-які випробування і знайдемо свій еліксир щастя.",
        "Напиши затишне повідомлення про те, як класно просто прогулятися ввечері нашою Полтавою, взяти смачну каву і насолоджуватися моментом удвох.",
        "Напиши дуже короткий, добрий і смішний жарт, щоб просто підняти настрій мені та Насосику посеред рутинного дня.",
        "Напиши життєве повідомлення про те, як іноді важко фокусуватися, коли пухнасті коти вимагають уваги і лізуть до нас, але це найкраща проблема у світі.",
        "Напиши амбітне і зухвале повідомлення про те, що одного дня наш проект PushUp ScrollDown розірве Google Play, а поки що треба просто впевнено робити свою справу.",
        "Напиши повідомлення так, ніби ми головні герої кіберпанк-аніме, які всю ніч фіксили баги під неоновими вивісками й вижили тільки завдяки каві та любові.",

        "Напиши смішне повідомлення про те, як кіт випадково став головним QA-тестувальником нашого додатку й знаходить баги швидше за нас.",

        "Напиши тепле повідомлення у вайбі Studio Ghibli. Наче за вікном дощ, вдома тепло, коти сплять поруч, а ми просто щасливі бути разом.",

        "Напиши коротке повідомлення так, ніби наші стосунки — це co-op гра, де ми разом проходимо навіть найскладніші рейди життя.",

        "Напиши повідомлення в стилі дуже пафосного JRPG, де навіть похід за молоком у магазин — це епічний квест S-рівня.",

        "Напиши мотиваційне повідомлення про те, що навіть якщо сьогодні все валиться, ми все одно повернемося сильнішими, як персонажі після таймскіпу в аніме.",

        "Напиши повідомлення так, ніби PushUp ScrollDown — це легендарний стартап, про який через 10 років будуть знімати документалки Netflix.",

        "Напиши затишне повідомлення про нічний кодинг, тиху музику, світло монітора й кота, який ліг прямо на клавіатуру у найважливіший момент.",

        "Напиши повідомлення в стилі Souls-like гри: що життя складне, боси жорсткі, але ми вже занадто далеко зайшли, щоб здаватись.",

        "Напиши дуже дивне, але смішне повідомлення, де кава — це легендарний артефакт, а ранкове пробудження — щоденна битва за виживання.",

        "Напиши романтичне повідомлення так, ніби ми двоє хакерів, які випадково зламали систему й знайшли одне одного серед мільйонів людей.",

        "Напиши повідомлення з вайбом Discord-нічки: меми, сміх, випадкові геніальні ідеї о 3 ранку і повне нерозуміння, як ми досі не спимо.",

        "Напиши коротке повідомлення про те, що наші коти таємно керують квартирою, а ми просто NPC, які оплачують їм корм.",

        "Напиши епічне повідомлення в стилі трейлера до фільму: 'У світі, де дедлайни неможливо перемогти... з'являються двоє людей з кавою та впертістю'.",

        "Напиши повідомлення так, ніби ШІ трохи закохався в нашу атмосферу й тепер сам хоче жити у нас вдома, пити еспресо-тонік і гладити котів.",

        "Напиши максимально абсурдне мотиваційне повідомлення, де успіх вимірюється кількістю випитої кави, виживших нервових клітин і погладжених котів.",

        "Напиши повідомлення в стилі League of Legends, ніби ми щойно виграли ranked-життя завдяки командній роботі й моральній підтримці одне одного.",

        "Напиши повідомлення у вайбі 'ніч перед релізом': паніка, баги, сміх, втома, але в кінці — гордість за те, що ми це зробили разом.",

        "Напиши повідомлення так, ніби Всесвіт намагався нас зламати, але ми відповіли йому: 'git push origin main'.",

        "Напиши тепле повідомлення про те, що справжній дім — це не місце, а момент, коли ми сидимо разом, а коти десь поруч влаштовують хаос."
    )

    val finalPrompt = "${prompts.random()} ВАЖЛИВО: Напиши лише сам текст повідомлення українською мовою. Жодних вступних слів, без пояснень, без лапок і без привітань на початку."
    val fallbackMessages = listOf(
        "Котики, ШІ зараз відпочиває, але ви все одно найкращі! Гарного дня і найсмачнішої кави ☕️",
        "Сьогодні без штучного інтелекту, бо він пішов гратися з кошеням 🐈. Бажаю вам шалених успіхів з PushUp ScrollDown!",
        "Навіть коли технології дають збій, ви впораєтеся з усіма цілями! Насосик, ти супер ❤️",
        "Мотивація на сьогодні: ви самі творці свого успіху! Обіймаю вас і бажаю продуктивного дня 🚀"
    )

    return fetchFromGemini(finalPrompt, fallbackMessages)
}

// 2. Генерація щоденної цитати
fun generateQuoteOfTheDay(): String {
    val prompt = "Знайди або згенеруй одну дуже потужну, глибоку і надихаючу цитату. " +
            "Це може бути цитата відомої людини (стоїка, підприємця, філософа, письменника) або просто сильна життєва мудрість. " +
            "Напиши тільки текст цитати та ім'я автора українською мовою. Без жодних зайвих слів чи вступів."

    val fallbackMessages = listOf(
        "«Навіть найдовший шлях починається з першого кроку.» — Лао-цзи",
        "«Те, що нас не вбиває, робить нас сильнішими.» — Фрідріх Ніцше",
        "«Успіх — це здатність крокувати від однієї невдачі до іншої, не втрачаючи ентузіазму.» — Вінстон Черчилль"
    )

    return fetchFromGemini(prompt, fallbackMessages)
}