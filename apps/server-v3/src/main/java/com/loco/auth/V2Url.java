package com.loco.auth;

import java.util.Set;
import java.util.regex.Pattern;

// validator.js 13.15.35 isURL({require_tld:false})에 해당한다. MIT 허가는 resources/licenses에 있다.
final class V2Url {
    private static final Pattern SCHEME = Pattern.compile("^([a-z][a-z0-9+.-]*):", Pattern.CASE_INSENSITIVE);
    private V2Url() { }
    static boolean valid(String value) {
        if (value == null || value.isEmpty() || value.length() > 2084 || Pattern.compile("[\\s<>\\ufeff]", Pattern.UNICODE_CHARACTER_CLASS).matcher(value).find()) return false;
        if (value.startsWith("mailto:")) return false;
        String url = value.split("[?#]", -1)[0];
        var match = SCHEME.matcher(url);
        boolean explicit = false;
        if (match.find()) {
            String tail = url.substring(match.end());
            String authority = tail.split("/", -1)[0];
            int at = authority.indexOf('@');
            boolean auth = !tail.startsWith("//") && at >= 0 && authority.substring(0, at).matches("[a-zA-Z0-9_.%:-]*")
                    && !Pattern.compile("%[0-9a-fA-F]{2}").matcher(authority.substring(0, at)).find();
            boolean port = !tail.startsWith("//") && at < 0 && tail.matches("[0-9][\\s\\S]*");
            if (!auth && !port) {
                if (!Set.of("http", "https", "ftp").contains(match.group(1).toLowerCase(java.util.Locale.ROOT))) return false;
                explicit = true; url = tail;
            }
        }
        if (url.startsWith("//")) { if (!explicit) return false; url = url.substring(2); }
        String host = url.split("/", -1)[0];
        int at = host.indexOf('@');
        if (at >= 0) {
            String auth = host.substring(0, at);
            if (auth.isEmpty() || auth.equals(":") || auth.split(":", -1).length > 2) return false;
            host = host.substring(at + 1);
        }
        String port = null;
        if (host.startsWith("[")) {
            var ipv6 = Pattern.compile("^\\[([^]]+)](?::([0-9]+))?$").matcher(host);
            if (!ipv6.matches()) return false;
            port = ipv6.group(2);
            if (!validPort(port)) return false;
            try {
                if (!ipv6.group(1).contains(":")) return false;
                java.net.InetAddress.getByName(ipv6.group(1));
                return true;
            }
            catch (Exception ignored) { return false; }
        }
        int colon = host.indexOf(':');
        if (colon >= 0) { port = host.substring(colon + 1); host = host.substring(0, colon); }
        if (!validPort(port)) return false;
        for (String label : host.split("\\.", -1)) {
            if (label.isEmpty() || label.length() > 63 || label.startsWith("-") || label.endsWith("-")
                    || label.chars().anyMatch(c -> c >= 0xff01 && c <= 0xff5e)
                    || !label.chars().allMatch(c -> c >= 0xa1 || c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '-')) return false;
        }
        return true;
    }
    private static boolean validPort(String port) {
        if (port == null || port.isEmpty()) return true;
        try { return port.matches("[0-9]+") && Long.parseLong(port) > 0 && Long.parseLong(port) <= 65535; }
        catch (NumberFormatException ignored) { return false; }
    }
}
