-- AI 会话表增加「系统上下文快照」：同会话内复用，新会话拉新快照
ALTER TABLE ai_chat_session ADD COLUMN system_context TEXT NULL;
