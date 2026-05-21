import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch

fun main() {
    // 1. Ініціалізація вбудованого HTTP-сервера для підтримки активності на хостингу
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    HttpServer.create(InetSocketAddress(port), 0).apply {
        createContext("/") { ex -> 
            val res = "Bot is running!".toByteArray()
            ex.sendResponseHeaders(200, res.size.toLong())
            ex.responseBody.use { it.write(res) }
        }
        start()
    }
    
    // 2. Ініціалізація бази даних
    initDatabase()

    // 3. Створення бота та реєстрація обробників подій Telegram
    val bot = bot {
        token = System.getenv("BOT_TOKEN") ?: "ТВІЙ_ТОКЕН"
        dispatch {
            setupBotHandlers()
        }
    }
    
    // 4. Запуск фонових планувальників розсилок
    startScheduler(bot)

    // 5. Запуск пулінгу бота
    bot.startPolling()
}