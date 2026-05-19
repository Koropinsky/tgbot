# 1. Етап збірки
FROM eclipse-temurin:17-jdk-alpine AS builder
WORKDIR /app
COPY . .
# Додаємо права на виконання для gradlew (бо ти пушиш з Windows)
RUN chmod +x ./gradlew
# Збираємо Fat JAR
RUN ./gradlew shadowJar --no-daemon

# 2. Етап запуску (тільки чиста Java і зібраний бот)
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=builder /app/build/libs/*-all.jar bot.jar
# Відкриваємо порт, щоб Render не приспав бота
EXPOSE 8080
CMD ["java", "-jar", "bot.jar"]