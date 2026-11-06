package dev.harness.data.session

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    /** CHAT, PLANNING or VERIFY. */
    val mode: String,
    val model: String,
    @ColumnInfo(name = "messages_json") val messagesJson: String,
    @ColumnInfo(name = "todo_json") val todoJson: String,
    /** ACTIVE, FINISHED or CLOSED. */
    val status: String,
    /** Comma-separated tag list. */
    val tags: String = "",
    @ColumnInfo(name = "message_count") val messageCount: Int = 0,
    /** Session this one was forked from, if any. */
    @ColumnInfo(name = "parent_id") val parentId: String? = null,
    /** Tool names disabled for this session, comma-separated. */
    @ColumnInfo(name = "disabled_tools") val disabledTools: String = "",
)
