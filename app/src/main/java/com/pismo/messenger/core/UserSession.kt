package com.pismo.messenger.core

/**
 * Данные залогиненного пользователя. Прямой перенос UserSession.cs,
 * включая режим «войти за пользователя» для роли admin.
 */
object UserSession {

    var userId: Int = 0
    var userName: String = ""
    var role: String = ""          // "admin" | "teacher"

    var impersonatedId: Int = 0
    var impersonatedName: String = ""

    val isImpersonating: Boolean get() = impersonatedId > 0

    /** Эффективный ID с учётом impersonation. */
    val effectiveId: Int get() = if (isImpersonating) impersonatedId else userId

    val effectiveName: String get() = if (isImpersonating) impersonatedName else userName

    val isAdmin: Boolean get() = role.equals("admin", ignoreCase = true)

    /** Админ работает «от себя» — тогда сайдбар показывает всех пользователей. */
    val isAdminRootView: Boolean get() = isAdmin && !isImpersonating

    fun clear() {
        userId = 0
        userName = ""
        role = ""
        stopImpersonating()
    }

    fun stopImpersonating() {
        impersonatedId = 0
        impersonatedName = ""
    }
}
