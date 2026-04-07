package org.ops.netpulse.config;

import java.util.List;

/** 侧栏菜单编码，与前端 Layout 一致；管理员拥有全部，普通用户仅显示已分配的菜单。 */
public final class MenuConstants {

    public static final List<String> ALL_MENU_CODES = List.of(
            "dashboard",
            "devices",
            "topology",
            "batch-command",
            "network-ai-command",
            "alerts",
            "metrics",
            // 侧栏合并为「系统巡检」一项，仅用 inspection；后两项兼容未迁移的 role_menu
            "inspection",
            "system-inspection",
            "inspection-reports",
            "ai-assistant",
            "audit",
            "backup",
            "users",
            "system"
    );

    private MenuConstants() {}
}
