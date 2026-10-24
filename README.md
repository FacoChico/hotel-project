# Hotel Project

REST API системы бронирования отелей. Один репозиторий содержит четыре
независимых Spring Boot приложения, каждое собирается своим Gradle Wrapper
без исходников, библиотек и корневой сборки остальных приложений.

## Состояние реализации

Реализован инфраструктурный каркас: Eureka Server,
Gateway с явными маршрутами, два бизнес-приложения с отдельными H2.
В Booking Service добавлены сущность пользователя, роли USER/ADMIN и репозиторий,
username уникален с учётом регистра, поле password предназначено для хеша пароля.
Booking Service выпускает JWT с подписью RS256 и проверяет токены на входящих
запросах. Регистрация и вход доступны через Gateway, пароли хранятся как хеши
bcrypt. Права доступа разделены между USER и ADMIN. Управление пользователями
и бронирования пока не реализованы, поэтому разрешённые запросы к этим маршрутам
получают 404. Без токена защищённые маршруты Booking Service возвращают 401.
В Hotel Service проверка JWT пока не добавлена.

## Стек

- Java 21, Spring Boot 4.1.1, Spring Cloud BOM 2025.1.3.
- Gradle 9.2.1, Groovy DSL, отдельный Wrapper с SHA-256 дистрибутива у каждого приложения.
- Spring Cloud Gateway Server Web MVC, Eureka, LoadBalancer.
- Spring MVC, Spring Data JPA, H2 in-memory, Bean Validation.
- Spring Security Resource Server и JWT в Booking Service.
- JUnit Jupiter; HTTP-проверки маршрутизации с локальными тестовыми backend-серверами.
- Flyway.
- Lombok.

Версии и набор зависимостей объявлены в build.gradle каждого приложения.
H2 Console отключена, Open Session in View отключён. Инфраструктурные
приложения не подключают JPA и H2. OpenAPI будет добавлен вместе с документацией API.

Схема Booking Service создаётся Flyway из миграций в
booking-service/src/main/resources/db/migration. Hibernate использует
`ddl-auto: validate` и проверяет соответствие сущностей схеме, JPA-тесты
используют те же миграции.
Hotel Service пока не содержит бизнес-таблиц, его миграции появятся вместе с сущностями отелей и номеров.

## Сборка

Требуются JDK 21 и доступ к репозиториям Gradle/Maven Central при первой сборке.
Глобально установленный Gradle не нужен. Из каталога любого приложения:

```sh
./gradlew build
```

В Windows используется gradlew.bat. Для запуска тестов отдельно: ./gradlew test.

## Локальный запуск

Приложения запускаются из своих каталогов, каждое в отдельном терминале.
Для Booking Service предварительно задаются ключи JWT, как описано ниже.

| Порядок | Каталог          | Команда           | Порт |
|---------|------------------|-------------------|------|
| 1       | discovery-server | ./gradlew bootRun | 8761 |
| 2       | hotel-service    | ./gradlew bootRun | 8081 |
| 3       | booking-service  | ./gradlew bootRun | 8082 |
| 4       | api-gateway      | ./gradlew bootRun | 8080 |

Eureka Dashboard: http://localhost:8761/. Регистрация и обновление клиентского
кэша выполняются асинхронно: сразу после запуска Gateway может вернуть 503.
Health каждого приложения доступен по /actuator/health, информация — /actuator/info.
В Booking Service доступ к info требует токен ADMIN. Доступны только эти два
Actuator endpoint, детали health не раскрываются.

| Переменная окружения | По умолчанию                  | Назначение                                           |
|----------------------|-------------------------------|------------------------------------------------------|
| SERVER_PORT          | По таблице выше               | Порт конкретного приложения                          |
| SERVER_ADDRESS       | 127.0.0.1                     | Адрес привязки HTTP-сервера                          |
| EUREKA_URL           | http://localhost:8761/eureka/ | Адрес реестра для клиентов; завершающий / обязателен |
| SERVICE_HOSTNAME     | localhost                     | Имя, которое клиент регистрирует в Eureka            |
| DB_PASSWORD          | Пусто                         | Пароль локальной H2 бизнес-сервиса                   |

Конфигурация предназначена для локального учебного стенда. При контейнеризации
потребуется задать SERVER_ADDRESS и доступный другим контейнерам SERVICE_HOSTNAME.
Данные H2 теряются при остановке процесса; сценарий частичного восстановления
после потери одной БД не поддерживается; стенд требуется перезапустить целиком.

## JWT в Booking Service

Токен действует один час и содержит идентификатор пользователя в `sub`, роли,
issuer, audience и время выпуска. При проверке времени допускается расхождение
часов до 60 секунд. Аутентификация использует заголовок `Authorization: Bearer`.
Сессии и cookies для неё не используются.

Для запуска требуется пара RSA-ключей размером не менее 2048 бит. Пример
создания из корня репозитория с OpenSSL:

```sh
mkdir -p secrets
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out secrets/jwt-private.pem
openssl pkey -in secrets/jwt-private.pem -pubout -out secrets/jwt-public.pem
chmod 600 secrets/jwt-private.pem
```

Публичный ключ имеет формат X.509 PEM, приватный — PKCS#8 PEM без шифрования.
Каталог secrets исключён из Git. Следующие переменные задаются в окружении
Booking Service. Пути в примере вычисляются из корня репозитория:

```sh
export JWT_PUBLIC_KEY="file:$(pwd)/secrets/jwt-public.pem"
export JWT_PRIVATE_KEY="file:$(pwd)/secrets/jwt-private.pem"
```

`JWT_ISSUER` по умолчанию равен `hotel-booking`, `JWT_AUDIENCE` — `hotel-api`.
При отсутствии ключей, неверном формате или несовпадающей паре сервис не запускается.

Публичными остаются GET /actuator/health и POST /api/users/v1/register,
/api/users/v1/auth. Маршруты управления пользователями требуют ADMIN,
маршруты бронирований — USER. Остальные пути и методы закрыты.
Ошибки доступа возвращаются как `application/problem+json`: 401 при отсутствии
валидного токена и 403 при недостаточных правах.

## Регистрация и вход

Версия API задаётся в маппингах методов после пути ресурса, например
`/api/users/v1/register`. Такая же структура используется для остальных
публичных маршрутов. Actuator и Eureka сохраняют свои стандартные адреса.

Оба запроса принимают JSON с полями `username` и `password`. Username уникален
с учётом регистра и содержит от 3 до 64 латинских букв, цифр или символов `_.-`.
Пароль занимает от 12 до 72 байт в UTF-8 и используется без обрезания пробелов
или нормализации. Неизвестные поля отклоняются, роль при регистрации всегда USER.

| Метод и путь | Ответ |
| --- | --- |
| POST /api/users/v1/register | 201, объект `user` с id, username, role и объект `token` |
| POST /api/users/v1/auth | 200, объект с accessToken, tokenType: Bearer и expiresIn: 3600 |

Маршруты доступны на Gateway (порт 8080) и напрямую в Booking Service (порт 8082).
Для запросов с подготовленным JSON-файлом secrets/credentials.json:

```sh
curl http://localhost:8080/api/users/v1/register -H 'Content-Type: application/json' --data-binary @secrets/credentials.json
curl http://localhost:8080/api/users/v1/auth -H 'Content-Type: application/json' --data-binary @secrets/credentials.json
```

Неверные данные запроса возвращают 400. Ошибки в полях перечисляются в `violations`
без введённых значений. Занятый username даёт 409 с кодом `USERNAME_ALREADY_EXISTS`,
а неизвестный пользователь и неверный пароль — одинаковый ответ 401
`INVALID_CREDENTIALS`. Пароли и хеши отсутствуют в ответах.
