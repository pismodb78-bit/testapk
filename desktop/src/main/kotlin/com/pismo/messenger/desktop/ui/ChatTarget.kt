package com.pismo.messenger.desktop.ui

import com.pismo.messenger.data.model.Scope

/**
 * С кем открыта переписка: человек или группа.
 *
 * Личные чаты и группы отличаются ровно тремя вещами — какой таблицей
 * читать, кому слать и есть ли «прочитано». Всё остальное в ленте у них
 * общее, поэтому экран один, а различия собраны здесь.
 */
data class ChatTarget(
    val id: Int,
    val name: String,
    val isGroup: Boolean,
) {
    val scope: Scope get() = if (isGroup) Scope.GROUP else Scope.DM
}
