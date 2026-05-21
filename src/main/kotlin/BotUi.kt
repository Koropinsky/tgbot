import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.InlineKeyboardMarkup
import com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton
import java.text.SimpleDateFormat
import java.util.Locale

fun showShoppingList(bot: Bot, chatId: Long) {
    val list = getShoppingList()
    val buttons = list.map { (id, item) -> listOf(InlineKeyboardButton.CallbackData("❌ $item", "del_shop_$id")) }.toMutableList()
    buttons.add(listOf(InlineKeyboardButton.CallbackData("➕ Додати покупку", "start_add_shop")))
    bot.sendMessage(ChatId.fromId(chatId), if (list.isEmpty()) "Список покупок порожній." else "Ваш список покупок:", replyMarkup = InlineKeyboardMarkup.create(buttons))
}

fun showGoalsList(bot: Bot, chatId: Long) {
    val list = getGoalsList()
    val buttons = list.map { (id, goal) -> listOf(InlineKeyboardButton.CallbackData("✅ $goal", "del_goal_$id")) }.toMutableList()
    buttons.add(listOf(InlineKeyboardButton.CallbackData("➕ Додати ціль", "start_add_goal")))
    bot.sendMessage(ChatId.fromId(chatId), if (list.isEmpty()) "Цілей поки немає." else "Наші довгострокові цілі:", replyMarkup = InlineKeyboardMarkup.create(buttons))
}

fun updateShoppingMessage(bot: Bot, chatId: Long, messageId: Long) {
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

fun updateGoalsMessage(bot: Bot, chatId: Long, messageId: Long) {
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

// === УНІВЕРСАЛЬНИЙ ТАСК-ТРЕКЕР (UI) ===

fun showTasksMainMenu(bot: Bot, chatId: Long) {
    val keyboard = InlineKeyboardMarkup.create(
        listOf(
            listOf(InlineKeyboardButton.CallbackData("👤 Таски Я", "tasks_ya"), InlineKeyboardButton.CallbackData("👤 Таски Н", "tasks_n")),
            listOf(InlineKeyboardButton.CallbackData("📂 Проекти", "projects_list"))
        )
    )
    bot.sendMessage(ChatId.fromId(chatId), "💻 **Завдання та спільні проекти**\nОберіть потрібний розділ:", replyMarkup = keyboard)
}

fun updateTasksMainMenu(bot: Bot, chatId: Long, messageId: Long) {
    val keyboard = InlineKeyboardMarkup.create(
        listOf(
            listOf(InlineKeyboardButton.CallbackData("👤 Таски Я", "tasks_ya"), InlineKeyboardButton.CallbackData("👤 Таски Н", "tasks_n")),
            listOf(InlineKeyboardButton.CallbackData("📂 Проекти", "projects_list"))
        )
    )
    bot.editMessageText(ChatId.fromId(chatId), messageId, text = "💻 **Завдання та спільні проекти**\nОберіть потрібний розділ:")
    bot.editMessageReplyMarkup(ChatId.fromId(chatId), messageId, replyMarkup = keyboard)
}

fun showPersonalTasks(bot: Bot, chatId: Long, assignee: String) {
    val name = if (assignee == "YA") "Я" else "Насосик (Н)"
    val list = getTasks(null, assignee)
    val buttons = list.map { (id, task) -> listOf(InlineKeyboardButton.CallbackData("❌ $task", "del_task_personal_${assignee}_$id")) }.toMutableList()
    buttons.add(listOf(InlineKeyboardButton.CallbackData("➕ Додати завдання", "add_task_personal_${assignee.lowercase()}")))
    buttons.add(listOf(InlineKeyboardButton.CallbackData("⬅️ Назад", "tasks_main")))
    val text = if (list.isEmpty()) "Список особистих завдань ($name) порожній." else "👤 **Особисті завдання ($name):**"
    bot.sendMessage(ChatId.fromId(chatId), text, replyMarkup = InlineKeyboardMarkup.create(buttons))
}

fun updatePersonalTasksMessage(bot: Bot, chatId: Long, messageId: Long, assignee: String) {
    val name = if (assignee == "YA") "Я" else "Насосик (Н)"
    val list = getTasks(null, assignee)
    val buttons = list.map { (id, task) -> listOf(InlineKeyboardButton.CallbackData("❌ $task", "del_task_personal_${assignee}_$id")) }.toMutableList()
    buttons.add(listOf(InlineKeyboardButton.CallbackData("➕ Додати завдання", "add_task_personal_${assignee.lowercase()}")))
    buttons.add(listOf(InlineKeyboardButton.CallbackData("⬅️ Назад", "tasks_main")))
    val text = if (list.isEmpty()) "Список особистих завдань ($name) порожній." else "👤 **Особисті завдання ($name):**"
    bot.editMessageText(ChatId.fromId(chatId), messageId, text = text)
    bot.editMessageReplyMarkup(ChatId.fromId(chatId), messageId, replyMarkup = InlineKeyboardMarkup.create(buttons))
}

fun showProjectsList(bot: Bot, chatId: Long) {
    val list = getProjects()
    val buttons = list.map { (id, name) -> listOf(InlineKeyboardButton.CallbackData("📂 $name", "project_view_$id")) }.toMutableList()
    buttons.add(listOf(InlineKeyboardButton.CallbackData("➕ Додати проект", "add_project_start")))
    buttons.add(listOf(InlineKeyboardButton.CallbackData("⬅️ Назад", "tasks_main")))
    bot.sendMessage(ChatId.fromId(chatId), "📂 **Спільні проекти:**\nОберіть проект для перегляду завдань або створіть новий:", replyMarkup = InlineKeyboardMarkup.create(buttons))
}

fun updateProjectsListMessage(bot: Bot, chatId: Long, messageId: Long) {
    val list = getProjects()
    val buttons = list.map { (id, name) -> listOf(InlineKeyboardButton.CallbackData("📂 $name", "project_view_$id")) }.toMutableList()
    buttons.add(listOf(InlineKeyboardButton.CallbackData("➕ Додати проект", "add_project_start")))
    buttons.add(listOf(InlineKeyboardButton.CallbackData("⬅️ Назад", "tasks_main")))
    bot.editMessageText(ChatId.fromId(chatId), messageId, text = "📂 **Спільні проекти:**\nОберіть проект для перегляду завдань або створіть новий:")
    bot.editMessageReplyMarkup(ChatId.fromId(chatId), messageId, replyMarkup = InlineKeyboardMarkup.create(buttons))
}

fun showProjectTasks(bot: Bot, chatId: Long, projectId: Int) {
    val name = getProjectName(projectId)
    val list = getTasks(projectId, null)
    val buttons = list.map { (id, task) -> listOf(InlineKeyboardButton.CallbackData("❌ $task", "del_task_project_${projectId}_$id")) }.toMutableList()
    buttons.add(listOf(InlineKeyboardButton.CallbackData("➕ Додати завдання", "add_task_project_start_$projectId")))
    buttons.add(listOf(InlineKeyboardButton.CallbackData("⬅️ Назад до проектів", "projects_list")))
    val text = if (list.isEmpty()) "У проекті «$name» поки немає завдань." else "📂 **Проект «$name»:**\nЗавдання проекту:"
    bot.sendMessage(ChatId.fromId(chatId), text, replyMarkup = InlineKeyboardMarkup.create(buttons))
}

fun updateProjectTasksMessage(bot: Bot, chatId: Long, messageId: Long, projectId: Int) {
    val name = getProjectName(projectId)
    val list = getTasks(projectId, null)
    val buttons = list.map { (id, task) -> listOf(InlineKeyboardButton.CallbackData("❌ $task", "del_task_project_${projectId}_$id")) }.toMutableList()
    buttons.add(listOf(InlineKeyboardButton.CallbackData("➕ Додати завдання", "add_task_project_start_$projectId")))
    buttons.add(listOf(InlineKeyboardButton.CallbackData("⬅️ Назад до проектів", "projects_list")))
    val text = if (list.isEmpty()) "У проекті «$name» поки немає завдань." else "📂 **Проект «$name»:**\nЗавдання проекту:"
    bot.editMessageText(ChatId.fromId(chatId), messageId, text = text)
    bot.editMessageReplyMarkup(ChatId.fromId(chatId), messageId, replyMarkup = InlineKeyboardMarkup.create(buttons))
}

// === КАЛЕНДАР ПОДІЙ (UI) ===

fun showCalendarEvents(bot: Bot, chatId: Long) {
    val list = getUpcomingCalendarEvents()
    val buttons = list.map { (id, date, text) -> listOf(InlineKeyboardButton.CallbackData("❌ [${formatDate(date)}] $text", "del_calendar_$id")) }.toMutableList()
    buttons.add(listOf(InlineKeyboardButton.CallbackData("➕ Додати подію", "start_add_calendar")))
    bot.sendMessage(ChatId.fromId(chatId), if (list.isEmpty()) "Календар майбутніх подій порожній." else "📅 **Календар сімейних подій:**", replyMarkup = InlineKeyboardMarkup.create(buttons))
}

fun updateCalendarMessage(bot: Bot, chatId: Long, messageId: Long) {
    val list = getUpcomingCalendarEvents()
    val buttons = list.map { (id, date, text) -> listOf(InlineKeyboardButton.CallbackData("❌ [${formatDate(date)}] $text", "del_calendar_$id")) }.toMutableList()
    buttons.add(listOf(InlineKeyboardButton.CallbackData("➕ Додати подію", "start_add_calendar")))
    bot.editMessageText(ChatId.fromId(chatId), messageId, text = if (list.isEmpty()) "Календар майбутніх подій порожній." else "📅 **Календар сімейних подій:**")
    bot.editMessageReplyMarkup(ChatId.fromId(chatId), messageId, replyMarkup = InlineKeyboardMarkup.create(buttons))
}

fun formatDate(date: java.sql.Date): String {
    return SimpleDateFormat("dd.MM", Locale.getDefault()).format(date)
}
