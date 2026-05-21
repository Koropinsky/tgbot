import java.net.URI
import java.sql.Connection
import java.sql.DriverManager

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

        // Додаємо колонку для оцінки (якщо її ще немає)
        try {
            getDatabaseConnection().use { conn ->
                conn.createStatement().execute("ALTER TABLE watched_list ADD COLUMN rating INT DEFAULT 0;")
            }
        } catch (e: Exception) {}

        println("Database initialized successfully.")
    } catch (e: Exception) { println("Database init error: ${e.message}") }
}

fun getWatchedListWithRatings(category: String): List<Pair<String, Int>> = mutableListOf<Pair<String, Int>>().apply {
    getDatabaseConnection().use { conn ->
        val rs = conn.prepareStatement("SELECT title, rating FROM watched_list WHERE category = ?").apply { setString(1, category) }.executeQuery()
        while (rs.next()) add(rs.getString("title") to rs.getInt("rating"))
    }
}

fun addWatchedItem(category: String, title: String, rating: Int) = getDatabaseConnection().use { conn ->
    conn.prepareStatement("INSERT INTO watched_list (category, title, rating) VALUES (?, ?, ?)").apply {
        setString(1, category)
        setString(2, title)
        setInt(3, rating)
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
