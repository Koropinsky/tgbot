import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.InlineKeyboardMarkup
import com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton

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
