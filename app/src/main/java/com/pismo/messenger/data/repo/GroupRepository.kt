package com.pismo.messenger.data.repo

import com.pismo.messenger.core.UserSession
import com.pismo.messenger.core.buildName
import com.pismo.messenger.data.db.Db
import com.pismo.messenger.data.db.bool
import com.pismo.messenger.data.db.str
import com.pismo.messenger.data.model.GroupMember
import com.pismo.messenger.data.model.UserBrief

/**
 * Групповые чаты — порт CreateGroupForm.cs, GroupMembersForm.cs и
 * групповой части MainForm_MessageActions.cs.
 *
 * Права как на ПК: добавлять участников может любой член группы,
 * исключать — только системный администратор PISMO; удалить группу —
 * её создатель или администратор.
 */
object GroupRepository {

    /** Кандидаты в новую группу — все, кроме себя. */
    suspend fun candidatesForNewGroup(): List<UserBrief> = Db.query(
        "SELECT id, Name, Surname, login FROM users WHERE id <> ? ORDER BY Name, Surname",
        UserSession.effectiveId
    ) { rs ->
        UserBrief(
            id = rs.getInt("id"),
            name = buildName(rs.str("Name"), rs.str("Surname"), rs.str("login")),
            login = rs.str("login"),
        )
    }

    /** Создаёт группу и добавляет создателя админом. Возвращает id группы. */
    suspend fun createGroup(name: String, memberIds: List<Int>): Int {
        val me = UserSession.effectiveId
        val groupId = Db.insert("INSERT INTO group_chats (name, created_by) VALUES (?, ?)", name, me)
        if (groupId <= 0) return 0

        Db.exec(
            "INSERT INTO group_members (group_id, user_id, is_admin) VALUES (?, ?, 1)",
            groupId, me
        )
        for (uid in memberIds) {
            if (uid == me) continue
            runCatching {
                Db.exec(
                    "INSERT INTO group_members (group_id, user_id, is_admin) VALUES (?, ?, 0)",
                    groupId, uid
                )
            }
        }
        return groupId
    }

    suspend fun members(groupId: Int): List<GroupMember> = Db.query(
        "SELECT u.id, TRIM(CONCAT(u.Name,' ',u.Surname)) AS full_name, u.login, gm.is_admin " +
                "FROM group_members gm JOIN users u ON u.id = gm.user_id " +
                "WHERE gm.group_id=? ORDER BY gm.is_admin DESC, full_name ASC",
        groupId
    ) { rs ->
        GroupMember(
            userId = rs.getInt("id"),
            name = rs.str("full_name").trim().ifBlank { rs.str("login") },
            isAdmin = rs.bool("is_admin"),
        )
    }

    /** Кандидаты на добавление — те, кого в группе ещё нет. */
    suspend fun candidatesToAdd(groupId: Int): List<UserBrief> = Db.query(
        "SELECT u.id, TRIM(CONCAT(u.Name,' ',u.Surname)) AS full_name, u.login FROM users u " +
                "WHERE u.id NOT IN (SELECT user_id FROM group_members WHERE group_id=?) " +
                "ORDER BY full_name, u.login",
        groupId
    ) { rs ->
        UserBrief(
            id = rs.getInt("id"),
            name = rs.str("full_name").trim().ifBlank { rs.str("login") },
            login = rs.str("login"),
        )
    }

    suspend fun addMembers(groupId: Int, userIds: List<Int>): Int {
        var added = 0
        for (uid in userIds) {
            val ok = runCatching {
                Db.exec(
                    "INSERT INTO group_members (group_id, user_id, is_admin) VALUES (?, ?, 0)",
                    groupId, uid
                )
            }.isSuccess
            if (ok) added++
        }
        return added
    }

    /** Исключение участника — только системный администратор. */
    suspend fun kickMember(groupId: Int, userId: Int) {
        Db.exec("DELETE FROM group_members WHERE group_id=? AND user_id=?", groupId, userId)
    }

    suspend fun leaveGroup(groupId: Int) {
        Db.exec(
            "DELETE FROM group_members WHERE group_id=? AND user_id=?",
            groupId, UserSession.effectiveId
        )
    }

    suspend fun isMember(groupId: Int): Boolean = runCatching {
        Db.scalarInt(
            "SELECT COUNT(*) FROM group_members WHERE group_id=? AND user_id=?",
            groupId, UserSession.effectiveId
        ) > 0
    }.getOrDefault(true)

    suspend fun createdBy(groupId: Int): Int =
        Db.scalarInt("SELECT created_by FROM group_chats WHERE id=?", groupId, default = -1)

    /** Удалить группу может только создатель или системный администратор. */
    suspend fun canDeleteGroup(groupId: Int): Boolean =
        createdBy(groupId) == UserSession.effectiveId || UserSession.isAdmin

    suspend fun deleteGroup(groupId: Int) {
        Db.exec("DELETE FROM group_messages WHERE group_id=?", groupId)
        Db.exec("DELETE FROM group_members WHERE group_id=?", groupId)
        Db.exec("DELETE FROM group_chats WHERE id=?", groupId)
    }

    /** Участники группы — для выбора собеседников в групповом звонке. */
    suspend fun callCandidates(groupId: Int): List<UserBrief> = Db.query(
        "SELECT gm.user_id, TRIM(CONCAT(u.Name,' ',u.Surname)) AS fullname, u.login " +
                "FROM group_members gm JOIN users u ON u.id = gm.user_id " +
                "WHERE gm.group_id=? AND gm.user_id <> ?",
        groupId, UserSession.effectiveId
    ) { rs ->
        UserBrief(
            id = rs.getInt("user_id"),
            name = rs.str("fullname").trim().ifBlank { rs.str("login") },
            login = rs.str("login"),
        )
    }
}
