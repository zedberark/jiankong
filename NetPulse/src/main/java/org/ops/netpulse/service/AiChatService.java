package org.ops.netpulse.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ops.netpulse.dto.InspectionItemRow;
import org.ops.netpulse.dto.InspectionReportDetailDto;
import org.ops.netpulse.entity.AlertHistory;
import org.ops.netpulse.entity.AlertRule;
import org.ops.netpulse.entity.AiChatMessage;
import org.ops.netpulse.entity.AiChatSession;
import org.ops.netpulse.entity.Device;
import org.ops.netpulse.repository.AlertHistoryRepository;
import org.ops.netpulse.repository.AiChatMessageRepository;
import org.ops.netpulse.repository.AiChatSessionRepository;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.ResourceAccessException;

import java.util.*;
import java.util.stream.Collectors;

/**
 * AI 运维助手：会话与消息持久化、调用千问/DeepSeek 对话。
 * chatOneShot：一次性对话不建会话不落库，用于网络 AI 命令/批量命令（transientChat=true）；
 * chat：创建或沿用会话，写入消息并调用 LLM，回复入库。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AiChatService {

    private final AiChatSessionRepository sessionRepository;
    private final AiChatMessageRepository messageRepository;
    private final DeviceService deviceService;
    private final DeviceStatsService deviceStatsService;
    private final SystemConfigService configService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.ops.netpulse.service.DeviceSshCollectService deviceSshCollectService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.ops.netpulse.service.SnmpStatsService snmpStatsService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AlertHistoryRepository alertHistoryRepository;
    @org.springframework.beans.factory.annotation.Value("${ai.llm.connect-timeout-ms:10000}")
    private int llmConnectTimeoutMs;
    @org.springframework.beans.factory.annotation.Value("${ai.llm.read-timeout-ms:45000}")
    private int llmReadTimeoutMs;
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 系统提示前缀：新会话拉取新快照，同一会话内沿用首次拉取的快照。告警以「告警通知」页的规则与历史为准。 */
    private static final String SYSTEM_PROMPT_PREFIX = "你是运维助手。请结合「当前对话上下文」与下文的「设备与监控信息」回答用户，回复要有针对性、可引用上文设备/告警数据。告警功能在「告警通知」页查看与处理，请根据上下文中的规则告警条数回答，勿误导；若用户问告警可引导至「告警通知」页。\n【输出要求】只输出你本轮的回复正文，可自然引用当前上下文（如「根据当前设备信息…」），但不要重复用户原句、不要输出整段对话历史、不要加「用户：」「助手：」等前缀，不要用 ** 包裹大段内容。回复简洁专业。\n\n【当前设备与监控信息】\n";

    /** AI 巡检：仅根据结构化巡检数据输出结论，不混入通用设备快照 */
    private static final String INSPECTION_SYSTEM_PROMPT =
            "你是资深网络与系统运维专家。用户将提供一次「系统巡检」的结构化数据（ICMP/TCP 可达性探测结果）。\n"
                    + "【输出结构】必须用中文，使用 Markdown 二级标题（##），且至少包含以下四段（顺序可微调，但小标题名称请保留或同义表达）：\n"
                    + "## 一、系统状态总览\n"
                    + "用 2～4 句话概括本次探测范围内设备整体在线/离线情况、是否存在大面积不可达或单点风险。\n"
                    + "## 二、健康度结论\n"
                    + "必须给出：①健康度等级（从「优秀 / 良好 / 一般 / 较差 / 危急」五选一，须与离线数、延迟偏高数相匹配）；"
                    + "②可选 0～100 的健康度评分（整数，与等级一致）；③用 1～2 句话说明判定依据（可引用用户提供的可达率、正常率等衍生指标）。\n"
                    + "## 三、主要风险与影响\n"
                    + "列出最值得优先处理的风险点（离线、高延迟对业务的潜在影响），勿编造数据中不存在的设备。\n"
                    + "## 四、处置与排查建议\n"
                    + "对离线、延迟偏高设备给出可操作的排查方向（链路、ACL、禁 ping、SNMP/SSH 端口、业务依赖等）。若全部正常，本段可写「当前无需紧急处置，建议保持例行监控」。\n"
                    + "【禁止】不要整段复述原始明细表，不要输出「用户：」「助手：」前缀，不要用代码块包裹全文。";

    /** 当前用户的会话列表，按创建时间倒序 */
    public List<AiChatSession> getSessions(String username) {
        if (username == null || username.isBlank()) return List.of();
        return sessionRepository.findByUsernameOrderByCreateTimeDesc(username);
    }

    /** 某会话的消息列表，校验会话归属当前用户 */
    public List<AiChatMessage> getMessages(Long sessionId, String username) {
        if (sessionId == null) return List.of();
        AiChatSession s = sessionRepository.findById(sessionId).orElse(null);
        if (s == null || !username.equals(s.getUsername())) return List.of();
        return messageRepository.findBySessionIdOrderByCreateTimeAsc(sessionId);
    }

    /** 创建新会话，标题默认「新会话」 */
    @Transactional
    public AiChatSession createSession(String username) {
        AiChatSession s = AiChatSession.builder().username(username).title("新会话").build();
        return sessionRepository.save(s);
    }

    /** 删除会话及其全部消息，校验归属 */
    @Transactional
    public void deleteSession(Long sessionId, String username) {
        if (sessionId == null || username == null || username.isBlank()) return;
        AiChatSession s = sessionRepository.findById(sessionId).orElse(null);
        if (s == null || !username.equals(s.getUsername())) return;
        messageRepository.deleteBySessionId(sessionId);
        sessionRepository.delete(s);
    }

    /** 一次性对话：不创建会话、不落库，用于网络 AI 命令/批量命令等，不会出现在 AI 运维助手会话列表 */
    public ChatResult chatOneShot(String username, String userMessage) {
        if (username == null || username.isBlank()) throw new IllegalArgumentException("需要登录用户");
        if (userMessage == null || userMessage.isBlank()) throw new IllegalArgumentException("消息不能为空");
        String context = buildContext();
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", SYSTEM_PROMPT_PREFIX + context));
        messages.add(Map.of("role", "user", "content", userMessage));
        String reply = callLlm(messages);
        if (reply == null) reply = "当前无法获取 AI 回复，请确认在「系统设置」中已配置并启用千问或 DeepSeek API。";
        reply = stripConversationEcho(reply);
        return new ChatResult(null, reply);
    }

    /**
     * 根据单次巡检报告的结构化数据调用 LLM 生成运维结论（不落会话库）。
     * 使用与运维助手相同的千问/DeepSeek 配置。
     */
    public String summarizeInspectionReport(String username, InspectionReportDetailDto detail) {
        if (username == null || username.isBlank()) throw new IllegalArgumentException("需要登录用户");
        if (detail == null) throw new IllegalArgumentException("报告不能为空");
        String userContent = buildInspectionUserContent(detail);
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", INSPECTION_SYSTEM_PROMPT));
        messages.add(Map.of("role", "user", "content", userContent));
        String reply = callLlm(messages, 2048);
        if (reply == null) reply = "";
        reply = stripConversationEcho(reply);
        return reply.isBlank()
                ? "当前无法生成 AI 巡检结论。请到「系统设置」- API 设置中启用千问或 DeepSeek，并检查网络。"
                : reply;
    }

    private static String buildInspectionUserContent(InspectionReportDetailDto d) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("【巡检时间】").append(d.getCreatedAt() != null ? d.getCreatedAt().toString() : "-").append('\n');
        sb.append("【范围】").append(d.getGroupName() == null || d.getGroupName().isBlank() ? "全部设备" : d.getGroupName()).append('\n');
        sb.append("【来源】").append(d.getSource() != null ? d.getSource() : "-");
        if (d.getScheduleLabel() != null && !d.getScheduleLabel().isBlank()) {
            sb.append(" · ").append(d.getScheduleLabel());
        }
        sb.append('\n');
        int total = d.getTotalCount();
        int ok = d.getOkCount();
        int warn = d.getWarnCount();
        int offline = d.getOfflineCount();
        sb.append("【统计】总数=").append(total)
                .append(" 正常=").append(ok)
                .append(" 延迟偏高=").append(warn)
                .append(" 离线=").append(offline);
        if (d.getDurationMs() != null) sb.append(" 探测耗时ms=").append(d.getDurationMs());
        sb.append('\n');
        appendInspectionDerivedMetrics(sb, total, ok, warn, offline);
        List<InspectionItemRow> items = d.getItems();
        if (items == null || items.isEmpty()) {
            sb.append("【明细】无设备明细。\n");
            return sb.toString();
        }
        sb.append("【离线设备】（至多列出前 30 台）\n");
        int off = 0;
        for (InspectionItemRow it : items) {
            if (!"offline".equals(it.getStatus())) continue;
            sb.append("- ").append(nullToDash(it.getDeviceName())).append(" IP=").append(nullToDash(it.getIp()))
                    .append(" 类型=").append(nullToDash(it.getDeviceType())).append('\n');
            if (++off >= 30) break;
        }
        if (off == 0) sb.append("（无）\n");
        sb.append("【延迟偏高】（RTT>800ms，至多列出前 30 台）\n");
        int warnListed = 0;
        for (InspectionItemRow it : items) {
            if (!"warning".equals(it.getStatus())) continue;
            sb.append("- ").append(nullToDash(it.getDeviceName())).append(" IP=").append(nullToDash(it.getIp()))
                    .append(" RTT=").append(it.getRttMs() != null ? it.getRttMs() + "ms" : "-").append('\n');
            if (++warnListed >= 30) break;
        }
        if (warnListed == 0) sb.append("（无）\n");
        return sb.toString();
    }

    /** 供模型计算健康度：可达=正常+延迟偏高；与 Monitor 中「在线」语义一致 */
    private static void appendInspectionDerivedMetrics(StringBuilder sb, int total, int ok, int warn, int offline) {
        if (total <= 0) {
            sb.append("【衍生指标】无有效样本（总数为 0），健康度请结合业务说明为「无数据」或「无法评估」。\n");
            return;
        }
        int reachable = ok + warn;
        double reachPct = (100.0 * reachable) / total;
        double okPct = (100.0 * ok) / total;
        double warnPct = (100.0 * warn) / total;
        double offPct = (100.0 * offline) / total;
        sb.append("【衍生指标】可达设备数=").append(reachable).append("（正常+延迟偏高），可达率=")
                .append(String.format(java.util.Locale.CHINA, "%.1f%%", reachPct))
                .append("；正常率=").append(String.format(java.util.Locale.CHINA, "%.1f%%", okPct))
                .append("；延迟偏高占比=").append(String.format(java.util.Locale.CHINA, "%.1f%%", warnPct))
                .append("；离线占比=").append(String.format(java.util.Locale.CHINA, "%.1f%%", offPct))
                .append("。\n");
        sb.append("【说明】「正常」表示 RTT≤800ms 且可达；「延迟偏高」表示可达但 RTT>800ms；「离线」表示本次探测不可达。\n");
    }

    private static String nullToDash(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }

    /** 在指定会话中对话并入库，无 sessionId 则新建会话 */
    @Transactional
    public ChatResult chat(Long sessionId, String username, String userMessage) {
        return chat(sessionId, username, userMessage, false);
    }

    /** transientChat=true 时走 chatOneShot 不建会话；否则写入用户消息、调 LLM、写入助手回复 */
    @Transactional
    public ChatResult chat(Long sessionId, String username, String userMessage, boolean transientChat) {
        if (username == null || username.isBlank()) throw new IllegalArgumentException("需要登录用户");
        if (userMessage == null || userMessage.isBlank()) throw new IllegalArgumentException("消息不能为空");
        if (transientChat) return chatOneShot(username, userMessage);

        AiChatSession session;
        if (sessionId != null) {
            session = sessionRepository.findById(sessionId).orElse(null);
            if (session == null || !username.equals(session.getUsername()))
                throw new IllegalArgumentException("会话不存在或无权访问");
            // 首条消息时用首句更新会话标题（DeepSeek 式）
            if ("新会话".equals(session.getTitle()) && userMessage != null && !userMessage.isBlank()) {
                String title = userMessage.length() > 32 ? userMessage.substring(0, 32).trim() + "…" : userMessage.trim();
                session.setTitle(title);
                sessionRepository.save(session);
            }
        } else {
            session = sessionRepository.save(AiChatSession.builder().username(username).title(userMessage.length() > 32 ? userMessage.substring(0, 32).trim() + "…" : userMessage.trim()).build());
        }

        // 同一会话使用首次拉取的快照，新会话拉取新快照
        String context = (session.getSystemContext() != null && !session.getSystemContext().isEmpty())
                ? session.getSystemContext()
                : buildContext();
        if (session.getSystemContext() == null || session.getSystemContext().isEmpty()) {
            session.setSystemContext(context);
            sessionRepository.save(session);
        }
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", SYSTEM_PROMPT_PREFIX + context));
        for (AiChatMessage m : messageRepository.findBySessionIdOrderByCreateTimeAsc(session.getId())) {
            messages.add(Map.of("role", m.getRole(), "content", m.getContent()));
        }
        messages.add(Map.of("role", "user", "content", userMessage));

        messageRepository.save(AiChatMessage.builder().sessionId(session.getId()).role("user").content(userMessage).build());

        String reply = callLlm(messages);
        if (reply == null) reply = "当前无法获取 AI 回复，请确认在「系统设置」中已配置并启用千问或 DeepSeek API。";
        reply = stripConversationEcho(reply);
        messageRepository.save(AiChatMessage.builder().sessionId(session.getId()).role("assistant").content(reply).build());

        return new ChatResult(session.getId(), reply);
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    public static class ChatResult {
        private Long sessionId;
        private String reply;
    }

    public Long getOrCreateSessionId(Long sessionId, String username) {
        if (sessionId != null) {
            AiChatSession s = sessionRepository.findById(sessionId).orElse(null);
            if (s != null && username.equals(s.getUsername())) return s.getId();
        }
        AiChatSession newSession = createSession(username);
        return newSession.getId();
    }

    /** 构建注入 LLM 的上下文：每次调用时实时拉取设备列表、健康汇总、各设备 CPU/内存（与「实时指标」页一致，含 Linux 与网络设备） */
    private String buildContext() {
        StringBuilder sb = new StringBuilder();
        List<Device> devices = deviceService.findAll();
        Map<String, Long> health = deviceService.healthSummary().entrySet().stream().collect(Collectors.toMap(e -> e.getKey().name(), Map.Entry::getValue));
        Map<Long, DeviceStatsService.DeviceStats> stats = buildMergedStatsSnapshot(devices);

        long firingCount = getFiringAlertCount();
        sb.append("• 设备总数：").append(devices.size()).append(" 台\n");
        sb.append("• 在线（正常）：").append(health.getOrDefault("normal", 0L)).append(" 台\n");
        sb.append("• 离线：").append(health.getOrDefault("offline", 0L)).append(" 台\n");
        sb.append("• 未处理告警：").append(firingCount).append(" 条\n");
        sb.append("规则与历史请到「告警通知」页查看。\n\n");

        for (Device d : devices) {
            sb.append("- ").append(d.getName()).append(" | IP: ").append(d.getIp()).append(" | 类型: ").append(d.getType()).append(" | 状态: ").append(d.getStatus());
            DeviceStatsService.DeviceStats st = stats.get(d.getId());
            if (st != null) {
                if (st.getCpuPercent() != null) sb.append(" | CPU: ").append(String.format("%.1f", st.getCpuPercent())).append("%");
                if (st.getMemoryPercent() != null) sb.append(" | 内存: ").append(String.format("%.1f", st.getMemoryPercent())).append("%");
                if (st.getDiskPercent() != null) sb.append(" | 磁盘: ").append(String.format("%.1f", st.getDiskPercent())).append("%");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /** 当前未处理告警(告警中)条数，供助手上下文；无 repository 时返回 0 */
    private long getFiringAlertCount() {
        if (alertHistoryRepository == null) return 0L;
        try {
            AlertHistory.AlertStatus firing = AlertHistory.AlertStatus.firing;
            return alertHistoryRepository.countByStatusAndSeverity(firing, AlertRule.Severity.critical)
                    + alertHistoryRepository.countByStatusAndSeverity(firing, AlertRule.Severity.warning)
                    + alertHistoryRepository.countByStatusAndSeverity(firing, AlertRule.Severity.info);
        } catch (Exception e) {
            return 0L;
        }
    }

    /** 与「实时指标」一致：Linux 用 DeviceStatsService，网络设备用 Redis/SSH 缓存，合并后供助手随时获取当前系统信息 */
    private Map<Long, DeviceStatsService.DeviceStats> buildMergedStatsSnapshot(List<Device> devices) {
        Map<Long, DeviceStatsService.DeviceStats> merged = new HashMap<>();
        if (deviceStatsService != null) {
            merged.putAll(deviceStatsService.getStatsSnapshot());
        }
        for (Device d : devices) {
            if (d.getId() == null || merged.containsKey(d.getId())) continue;
            if (d.getType() != null && d.getType() == Device.DeviceType.server) continue;
            String ip = d.getIp() != null ? d.getIp().trim() : "";
            if (ip.isEmpty()) continue;
            DeviceStatsService.DeviceStats s = null;
            if (deviceSshCollectService != null) s = deviceSshCollectService.getStatsByIp(ip);
            if (s == null && snmpStatsService != null) {
                Map<Long, DeviceStatsService.DeviceStats> byRedis = snmpStatsService.getStatsFromRedisByIp(List.of(d));
                s = byRedis.get(d.getId());
            }
            if (s != null) merged.put(d.getId(), s);
        }
        return merged;
    }

    /**
     * 若模型把整段对话一起输出（用户：... 助手：...），只保留助手回复部分；否则原样返回，保留「结合当前上下文」的表述。
     * 仅在检测到明显的对话回显格式时才裁剪，避免误删正常引用上下文的内容。
     */
    private String stripConversationEcho(String reply) {
        if (reply == null || reply.isBlank()) return reply;
        String s = reply.trim();
        boolean hasUserMarker = s.contains("用户：") || s.contains("用户:") || s.contains("User:") || s.contains("User：");
        boolean hasAssistantMarker = s.contains("助手：") || s.contains("助手:") || s.contains("Assistant:") || s.contains("Assistant：");
        if (!hasUserMarker || !hasAssistantMarker) return reply;
        int lastAssistant = -1;
        for (String marker : new String[]{"助手：", "助手:", "Assistant:", "Assistant："}) {
            int i = s.lastIndexOf(marker);
            if (i >= 0) lastAssistant = Math.max(lastAssistant, i + marker.length());
        }
        if (lastAssistant > 0) {
            String tail = s.substring(lastAssistant).trim();
            if (!tail.isEmpty()) return tail;
        }
        if (s.startsWith("用户：") || s.startsWith("用户:") || s.startsWith("User:") || s.startsWith("User：")) {
            int firstAsst = -1;
            for (String asst : new String[]{"助手：", "助手:", "Assistant:", "Assistant："}) {
                int i = s.indexOf(asst);
                if (i > 0) firstAsst = firstAsst < 0 ? i : Math.min(firstAsst, i);
            }
            if (firstAsst > 0) {
                String after = s.substring(firstAsst).trim();
                return stripConversationEcho(after);
            }
        }
        return reply;
    }

    /** 根据系统配置选择千问或 DeepSeek 调用 LLM，返回助手回复内容 */
    private String callLlm(List<Map<String, String>> messages) {
        return callLlm(messages, 1024);
    }

    private String callLlm(List<Map<String, String>> messages, int maxTokens) {
        String qwenEnabled = configService.getValue("api.qwen.enabled").orElse("0");
        String deepseekEnabled = configService.getValue("api.deepseek.enabled").orElse("0");
        List<String> errors = new ArrayList<>();

        if ("1".equals(qwenEnabled)) {
            String endpoint = normalizeEndpoint(
                    configService.getValue("api.qwen.endpoint").orElse("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"),
                    "qwen");
            String key = configService.getValue("api.qwen.key").orElse("");
            if (!key.isEmpty()) {
                String reply = tryProvider("千问", endpoint, key, "qwen-turbo", messages, errors, maxTokens);
                if (reply != null && !reply.isBlank()) return reply;
            }
        }
        if ("1".equals(deepseekEnabled)) {
            String endpoint = normalizeEndpoint(
                    configService.getValue("api.deepseek.endpoint").orElse("https://api.deepseek.com/v1/chat/completions"),
                    "deepseek");
            String key = configService.getValue("api.deepseek.key").orElse("");
            if (!key.isEmpty()) {
                String reply = tryProvider("DeepSeek", endpoint, key, "deepseek-chat", messages, errors, maxTokens);
                if (reply != null && !reply.isBlank()) return reply;
            }
        }

        if (!errors.isEmpty()) {
            String detail = String.join("；", errors);
            return "AI 接口调用失败：" + detail + "。请检查「系统设置」中的 API 配置与网络；如可用，建议同时启用千问与 DeepSeek 以自动容灾。";
        }
        return "当前无法获取 AI 回复。请到「系统设置」- API 设置中：1）勾选启用「千问」或「DeepSeek」；2）填写完整 endpoint；3）填写 API Key 并保存。";
    }

    private String tryProvider(
            String providerName,
            String endpoint,
            String key,
            String model,
            List<Map<String, String>> messages,
            List<String> errors,
            int maxTokens
    ) {
        try {
            return callOpenAiStyle(endpoint, key, model, messages, maxTokens);
        } catch (RestClientResponseException e) {
            String body = e.getResponseBodyAsString();
            String status = e.getStatusCode().toString();
            log.warn("LLM [{}] call failed: {} {}", providerName, status, body != null && !body.isEmpty() ? body : "[no body]");
            if (status.contains("404")) {
                errors.add(providerName + " endpoint 无效(404)");
            } else if (status.contains("401")) {
                errors.add(providerName + " API Key 无效或过期(401)");
            } else {
                errors.add(providerName + " 返回 " + status);
            }
            return null;
        } catch (ResourceAccessException e) {
            String msg = e.getMessage() != null ? e.getMessage() : "网络不可达";
            log.warn("LLM [{}] network error: {}", providerName, msg);
            if (msg.toLowerCase().contains("timed out")) {
                errors.add(providerName + " 连接超时");
            } else {
                errors.add(providerName + " 网络异常");
            }
            return null;
        } catch (Exception e) {
            log.warn("LLM [{}] call failed: {}", providerName, e.getMessage());
            errors.add(providerName + " 调用异常");
            return null;
        }
    }

    private String callOpenAiStyle(String endpoint, String apiKey, String model, List<Map<String, String>> messages, int maxTokens) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("max_tokens", Math.max(256, Math.min(maxTokens, 8192)));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);
        try {
            HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(body), headers);
            ResponseEntity<String> resp = buildTimeoutRestTemplate().exchange(endpoint, HttpMethod.POST, entity, String.class);
            if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) return null;
            JsonNode root = objectMapper.readTree(resp.getBody());
            JsonNode choices = root.path("choices");
            if (choices.isArray() && choices.size() > 0) {
                JsonNode msg = choices.get(0).path("message").path("content");
                return msg.asText("");
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("LLM response parse error: {}", e.getMessage());
        }
        return null;
    }

    private RestTemplate buildTimeoutRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Math.max(1000, llmConnectTimeoutMs));
        factory.setReadTimeout(Math.max(1000, llmReadTimeoutMs));
        return new RestTemplate(factory);
    }

    private String normalizeEndpoint(String raw, String provider) {
        String endpoint = raw == null ? "" : raw.trim();
        if (endpoint.isEmpty()) return endpoint;
        String lower = endpoint.toLowerCase();
        if (lower.contains("/chat/completions")) return endpoint;
        if ("qwen".equals(provider)) {
            if (lower.endsWith("/v1")) return endpoint + "/chat/completions";
            if (lower.endsWith("/compatible-mode")) return endpoint + "/v1/chat/completions";
            return endpoint + "/compatible-mode/v1/chat/completions";
        }
        if ("deepseek".equals(provider)) {
            if (lower.endsWith("/v1")) return endpoint + "/chat/completions";
            return endpoint + "/v1/chat/completions";
        }
        return endpoint;
    }
}
