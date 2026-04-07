package org.ops.netpulse.util;

/**
 * 解析 /etc/os-release 或 grep -E "^(NAME|ID|ID_LIKE|VERSION)" 输出，返回厂商展示名。
 * 供获取主机名写厂商、定时自动填厂商等场景复用，避免重复实现。
 */
public final class OsReleaseParser {

    private OsReleaseParser() {}

    /**
     * 解析 grep -E "^(NAME|ID|ID_LIKE|VERSION)" /etc/os-release 输出，返回厂商展示名。
     * 优先使用 NAME 字段（如 Red Hat Enterprise Linux、Ubuntu、Rocky Linux、openEuler、UnionTech OS、Kylin Linux Advanced Server）；
     * NAME 为空时再按 ID 映射或回退到 ID 首字母大写。
     *
     * @param osRelease grep 关键行输出，可为 null 或空
     * @return 展示名，无法解析时返回 null
     */
    public static String parseVendor(String osRelease) {
        if (osRelease == null || osRelease.isBlank()) return null;
        String id = null;
        String name = null;
        for (String line : osRelease.split("\n")) {
            String s = line.trim();
            if (s.startsWith("ID=")) {
                String v = unquote(s.substring(3).trim());
                if (v != null && !v.isEmpty()) id = v.toLowerCase();
            } else if (s.startsWith("NAME=")) {
                name = unquote(s.substring(5).trim());
            }
        }
        // 优先使用 NAME 字段，与系统展示一致（Red Hat Enterprise Linux、Ubuntu、Rocky Linux、openEuler、UnionTech OS、Kylin Linux Advanced Server 等）
        if (name != null && !name.isBlank()) return name;
        if (id == null || id.isEmpty()) return null;
        switch (id) {
            case "ubuntu": return "Ubuntu";
            case "debian": return "Debian";
            case "centos": return "CentOS";
            case "rocky": return "Rocky Linux";
            case "rhel": return "Red Hat Enterprise Linux";
            case "fedora": return "Fedora";
            case "suse": case "opensuse": case "opensuse-leap": case "opensuse-tumbleweed": return "SUSE";
            case "sles": return "SUSE Linux Enterprise Server";
            case "kylin": return "Kylin Linux Advanced Server";
            case "almalinux": return "AlmaLinux";
            case "ol": return "Oracle Linux";
            default: return capitalize(id);
        }
    }

    private static String unquote(String s) {
        if (s == null) return null;
        s = s.trim();
        if ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'")))
            return s.substring(1, s.length() - 1).trim();
        return s;
    }

    private static String capitalize(String id) {
        if (id == null || id.isEmpty()) return id;
        return id.substring(0, 1).toUpperCase() + id.substring(1);
    }
}
