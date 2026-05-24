import java.net.URI
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDate
import java.time.ZoneId

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
                CREATE TABLE IF NOT EXISTS projects (id SERIAL PRIMARY KEY, name TEXT NOT NULL UNIQUE);
                CREATE TABLE IF NOT EXISTS tasks (
                    id SERIAL PRIMARY KEY,
                    project_id INT REFERENCES projects(id) ON DELETE CASCADE,
                    assignee TEXT,
                    task_text TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS family_calendar (id SERIAL PRIMARY KEY, event_date DATE NOT NULL, event_text TEXT NOT NULL);
            """.trimIndent())

            // Додаємо проекти за замовчуванням
            try {
                conn.prepareStatement("INSERT INTO projects (name) VALUES ('PushUp ScrollDown') ON CONFLICT DO NOTHING").executeUpdate()
                conn.prepareStatement("INSERT INTO projects (name) VALUES ('тг бот') ON CONFLICT DO NOTHING").executeUpdate()
            } catch (e: Exception) {}
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

fun updateWatchedItemRating(category: String, title: String, rating: Int) = getDatabaseConnection().use { conn ->
    conn.prepareStatement("UPDATE watched_list SET rating = ? WHERE category = ? AND title = ?").apply {
        setInt(1, rating)
        setString(2, category)
        setString(3, title)
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

fun deleteShoppingItemByName(item: String): Boolean = getDatabaseConnection().use { conn ->
    val ps = conn.prepareStatement("DELETE FROM shopping_list WHERE LOWER(TRIM(item)) = LOWER(TRIM(?))")
    ps.setString(1, item)
    ps.executeUpdate() > 0
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

// === ПРОЕКТИ ===

fun getProjects(): List<Pair<Int, String>> = mutableListOf<Pair<Int, String>>().apply {
    getDatabaseConnection().use { conn ->
        val rs = conn.createStatement().executeQuery("SELECT id, name FROM projects ORDER BY id")
        while (rs.next()) add(rs.getInt("id") to rs.getString("name"))
    }
}

fun addProject(name: String): Int {
    getDatabaseConnection().use { conn ->
        val ps = conn.prepareStatement("INSERT INTO projects (name) VALUES (?) ON CONFLICT DO NOTHING RETURNING id")
        ps.setString(1, name)
        val rs = ps.executeQuery()
        if (rs.next()) return rs.getInt("id")
    }
    getDatabaseConnection().use { conn ->
        val ps = conn.prepareStatement("SELECT id FROM projects WHERE name = ?")
        ps.setString(1, name)
        val rs = ps.executeQuery()
        if (rs.next()) return rs.getInt("id")
    }
    return -1
}

fun getProjectName(id: Int): String {
    getDatabaseConnection().use { conn ->
        val ps = conn.prepareStatement("SELECT name FROM projects WHERE id = ?")
        ps.setInt(1, id)
        val rs = ps.executeQuery()
        if (rs.next()) return rs.getString("name")
    }
    return "Невідомий проект"
}

// === ЗАВДАННЯ (УНІВЕРСАЛЬНІ) ===

fun getTasks(projectId: Int?, assignee: String?): List<Pair<Int, String>> = mutableListOf<Pair<Int, String>>().apply {
    getDatabaseConnection().use { conn ->
        val sql = when {
            projectId != null -> "SELECT id, task_text FROM tasks WHERE project_id = ? ORDER BY id"
            assignee != null -> "SELECT id, task_text FROM tasks WHERE project_id IS NULL AND assignee = ? ORDER BY id"
            else -> return@use
        }
        val ps = conn.prepareStatement(sql)
        if (projectId != null) ps.setInt(1, projectId) else ps.setString(1, assignee)
        val rs = ps.executeQuery()
        while (rs.next()) add(rs.getInt("id") to rs.getString("task_text"))
    }
}

fun addTask(projectId: Int?, assignee: String?, taskText: String) = getDatabaseConnection().use { conn ->
    val ps = conn.prepareStatement("INSERT INTO tasks (project_id, assignee, task_text) VALUES (?, ?, ?)")
    if (projectId != null) ps.setInt(1, projectId) else ps.setNull(1, java.sql.Types.INTEGER)
    if (assignee != null) ps.setString(2, assignee) else ps.setNull(2, java.sql.Types.VARCHAR)
    ps.setString(3, taskText)
    ps.executeUpdate()
}

fun deleteTask(id: Int) = getDatabaseConnection().use { conn ->
    conn.prepareStatement("DELETE FROM tasks WHERE id = ?").apply {
        setInt(1, id)
        executeUpdate()
    }
}

fun deletePersonalTaskByName(assignee: String, taskText: String): Boolean = getDatabaseConnection().use { conn ->
    val ps = conn.prepareStatement("DELETE FROM tasks WHERE project_id IS NULL AND assignee = ? AND LOWER(TRIM(task_text)) = LOWER(TRIM(?))")
    ps.setString(1, assignee)
    ps.setString(2, taskText)
    ps.executeUpdate() > 0
}

// === СІМЕЙНИЙ КАЛЕНДАР ===

fun getUpcomingCalendarEvents(): List<Triple<Int, java.sql.Date, String>> = mutableListOf<Triple<Int, java.sql.Date, String>>().apply {
    getDatabaseConnection().use { conn ->
        val kyivDate = LocalDate.now(ZoneId.of("Europe/Kyiv"))
        val sqlDate = java.sql.Date.valueOf(kyivDate.toString())
        val ps = conn.prepareStatement("SELECT id, event_date, event_text FROM family_calendar WHERE event_date >= ? ORDER BY event_date, id")
        ps.setDate(1, sqlDate)
        val rs = ps.executeQuery()
        while (rs.next()) {
            add(Triple(rs.getInt("id"), rs.getDate("event_date"), rs.getString("event_text")))
        }
    }
}

fun getTodayCalendarEvents(): List<String> = mutableListOf<String>().apply {
    getDatabaseConnection().use { conn ->
        val kyivDate = LocalDate.now(ZoneId.of("Europe/Kyiv"))
        val sqlDate = java.sql.Date.valueOf(kyivDate.toString())
        val ps = conn.prepareStatement("SELECT event_text FROM family_calendar WHERE event_date = ? ORDER BY id")
        ps.setDate(1, sqlDate)
        val rs = ps.executeQuery()
        while (rs.next()) {
            add(rs.getString("event_text"))
        }
    }
}

fun addCalendarEvent(date: java.sql.Date, text: String) = getDatabaseConnection().use { conn ->
    conn.prepareStatement("INSERT INTO family_calendar (event_date, event_text) VALUES (?, ?)").apply {
        setDate(1, date)
        setString(2, text)
        executeUpdate()
    }
}

fun deleteCalendarEvent(id: Int) = getDatabaseConnection().use { conn ->
    conn.prepareStatement("DELETE FROM family_calendar WHERE id = ?").apply {
        setInt(1, id)
        executeUpdate()
    }
}
