package com.pismo.messenger.data.repo

import com.pismo.messenger.core.PasswordHasher
import com.pismo.messenger.core.RateLimiter
import com.pismo.messenger.core.Prefs
import com.pismo.messenger.core.UserSession
import com.pismo.messenger.core.buildName
import com.pismo.messenger.data.db.Db
import com.pismo.messenger.data.db.DbMigrator
import com.pismo.messenger.data.db.str

/**
 * Вход, регистрация и смена пароля — порт LoginForm.cs / RegisterForm.cs /
 * ChangePasswordForm.cs из ПК-версии.
 *
 * Главное: пароль НЕ сверяется в SQL (там мог остаться открытый текст).
 * Берём хеш по логину и проверяем в коде, а легаси-plaintext перехешируем
 * при первом успешном входе — точно так же, как это делает ПК.
 */
object AuthRepository {

    sealed interface LoginResult {
        data object Success : LoginResult
        data object BadCredentials : LoginResult

        /** Слишком много неудачных попыток — вход заперт на [seconds] секунд. */
        data class Locked(val seconds: Int) : LoginResult
        data class Error(val message: String) : LoginResult
    }

    suspend fun login(login: String, password: String): LoginResult {
        // Порт защиты из LoginForm.cs: пять промахов подряд — и вход
        // запирается с нарастающей задержкой. Подбор пароля через клиент
        // после этого теряет смысл, а живой человек разницы не замечает.
        val lock = RateLimiter.loginLockRemainingMs(login)
        if (lock > 0) return LoginResult.Locked(((lock + 999) / 1000).toInt())

        return try {
            val row = Db.queryFirst(
                "SELECT id, Name, Surname, role, password FROM users WHERE login=?",
                login
            ) { rs ->
                Quad(
                    rs.getInt("id"),
                    buildName(rs.str("Name"), rs.str("Surname"), login),
                    rs.str("role").lowercase(),
                    rs.str("password"),
                )
            } ?: run {
                RateLimiter.registerLoginFailure(login)
                return LoginResult.BadCredentials
            }

            if (!PasswordHasher.verify(password, row.stored)) {
                RateLimiter.registerLoginFailure(login)
                return LoginResult.BadCredentials
            }
            RateLimiter.registerLoginSuccess(login)

            UserSession.userId = row.id
            UserSession.userName = row.name.ifBlank { login }
            UserSession.role = row.role

            // Миграция легаси-пароля в PBKDF2 — не критична для входа.
            if (PasswordHasher.needsUpgrade(row.stored)) {
                runCatching {
                    Db.exec(
                        "UPDATE users SET password=? WHERE id=?",
                        PasswordHasher.hash(password), row.id
                    )
                }
            }

            // Схема подтягивается один раз за сессию, как DbMigrator.Run() на ПК.
            runCatching { DbMigrator.run() }

            LoginResult.Success
        } catch (e: Exception) {
            LoginResult.Error(e.message ?: "неизвестная ошибка")
        }
    }

    private data class Quad(val id: Int, val name: String, val role: String, val stored: String)

    /** Восстановление сессии по сохранённым данным («запомнить меня»). */
    suspend fun autoLogin(): Boolean {
        if (!Prefs.rememberMe) return false
        val l = Prefs.savedLogin
        val p = Prefs.savedPassword
        if (l.isBlank() || p.isBlank()) return false
        return login(l, p) is LoginResult.Success
    }

    sealed interface RegisterResult {
        data object Success : RegisterResult
        data class Invalid(val message: String) : RegisterResult
        data class Error(val message: String) : RegisterResult
    }

    /** Правила валидации дословно повторяют RegisterForm.cs. */
    suspend fun register(name: String, surname: String, login: String, password: String): RegisterResult {
        if (name.isBlank() || surname.isBlank() || login.isBlank() || password.isBlank())
            return RegisterResult.Invalid("Заполните все поля!")
        if (login.lowercase() == "admin")
            return RegisterResult.Invalid("Этот логин зарезервирован.")
        if (password.length < 8)
            return RegisterResult.Invalid("Пароль минимум 8 символов.")
        if (password == "12345678" || password == "87654321")
            return RegisterResult.Invalid("Пароль слишком предсказуем!")

        return try {
            val taken = Db.scalarInt("SELECT COUNT(*) FROM users WHERE login=?", login) > 0
            if (taken) return RegisterResult.Invalid("Этот логин уже занят.")

            Db.exec(
                "INSERT INTO users (login, password, Name, Surname, role) VALUES (?, ?, ?, ?, 'teacher')",
                login, PasswordHasher.hash(password), name, surname
            )
            RegisterResult.Success
        } catch (e: Exception) {
            RegisterResult.Error(e.message ?: "неизвестная ошибка")
        }
    }

    /** Смена пароля. Валидация — как в ChangePasswordForm.cs. */
    suspend fun changePassword(oldPass: String, newPass: String, confirm: String): RegisterResult {
        if (newPass.length < 8) return RegisterResult.Invalid("Пароль минимум 8 символов!")
        if (newPass == "12345678" || newPass == "87654321")
            return RegisterResult.Invalid("Пароль слишком предсказуем!")
        if (newPass.isBlank() || newPass.all { it == ' ' })
            return RegisterResult.Invalid("Пароль не может состоять из пробелов!")
        if (newPass != confirm) return RegisterResult.Invalid("Пароли не совпадают!")

        return try {
            val stored = Db.scalarString(
                "SELECT password FROM users WHERE id=?", UserSession.userId
            ) ?: return RegisterResult.Invalid("Пользователь не найден.")

            if (!PasswordHasher.verify(oldPass, stored))
                return RegisterResult.Invalid("Старый пароль неверен!")

            Db.exec(
                "UPDATE users SET password=? WHERE id=?",
                PasswordHasher.hash(newPass), UserSession.userId
            )
            // Чтобы «запомнить меня» не разлогинило после смены пароля.
            if (Prefs.rememberMe) Prefs.savedPassword = newPass
            RegisterResult.Success
        } catch (e: Exception) {
            RegisterResult.Error(e.message ?: "неизвестная ошибка")
        }
    }

    /** Данные пользователя по id — для impersonation и профиля. */
    suspend fun loadUser(id: Int): Triple<String, String, String>? = Db.queryFirst(
        "SELECT Name, Surname, login, role FROM users WHERE id=?", id
    ) { rs ->
        Triple(
            buildName(rs.str("Name"), rs.str("Surname"), rs.str("login")),
            rs.str("login"),
            rs.str("role").lowercase(),
        )
    }
}
