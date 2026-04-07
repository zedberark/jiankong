package org.ops.netpulse.repository;

import org.ops.netpulse.entity.AiChatSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AiChatSessionRepository extends JpaRepository<AiChatSession, Long> {

    List<AiChatSession> findByUsernameOrderByCreateTimeDesc(String username);
}
