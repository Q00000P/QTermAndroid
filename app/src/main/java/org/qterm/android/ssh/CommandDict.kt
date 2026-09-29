package org.qterm.android.ssh

/**
 * Встроенный словарь подсказок — 1:1 с CommandDict.common мака (скоуп
 * "server"). Тексты обязаны совпадать между платформами: скрытия
 * встроенных команд синкаются через cmdDictUser по тексту команды.
 */
object CommandDict {
    val COMMON: List<String> = listOf(
        "systemctl status", "systemctl restart", "systemctl stop", "systemctl start", "systemctl enable",
        "systemctl disable", "systemctl daemon-reload", "systemctl list-units", "journalctl -u",
        "journalctl -f", "journalctl -e", "journalctl --since", "reboot", "poweroff", "uptime",
        "uname -a", "hostnamectl", "timedatectl", "df -h", "du -sh", "free -h", "top", "htop",
        "ps aux", "kill", "killall", "lsof -i", "dmesg", "watch", "apt update", "apt upgrade",
        "apt install", "apt remove", "apt autoremove", "apt search", "apt list --installed",
        "dpkg -l", "opkg update", "opkg install", "opkg remove", "opkg list-installed", "opkg files",
        "ls -la", "cd", "cat", "less", "tail -f", "tail -n", "head", "grep -r", "find / -name",
        "chmod +x", "chmod 644", "chown", "ln -s", "mkdir -p", "rm -rf", "cp -r", "mv", "touch",
        "nano", "vi", "tar -xzf", "tar -czf", "unzip", "rsync -avz", "scp", "dd if=", "mount",
        "umount", "ip a", "ip r", "ip link", "ip neigh", "ss -tulpn", "ss -s", "ping", "ping -c 4",
        "traceroute", "mtr", "dig", "dig @127.0.0.1", "nslookup", "host", "curl -I", "curl -s",
        "curl -o", "wget", "nft list ruleset", "nft flush ruleset", "iptables -L -n -v",
        "iptables -t nat -L -n", "tcpdump -i", "arp -a", "ethtool", "networkctl", "wg", "wg show",
        "wg-quick up", "wg-quick down", "wg genkey", "wg pubkey", "awg show", "systemctl restart xray",
        "systemctl status xray", "journalctl -u xray -f", "x-ui", "x-ui status", "x-ui restart",
        "xray version", "xray run -test -config", "systemctl restart AdGuardHome", "systemctl status AdGuardHome",
        "unbound-control status", "unbound-control reload", "unbound-checkconf", "systemctl restart unbound",
        "nginx -t", "nginx -s reload", "systemctl restart nginx", "systemctl status nginx",
        "certbot renew", "certbot certificates", "caddy reload", "caddy validate", "docker ps",
        "docker ps -a", "docker logs -f", "docker restart", "docker stop", "docker exec -it",
        "docker compose up -d", "docker compose down", "docker compose logs -f", "docker compose pull",
        "docker images", "docker system prune", "git status", "git pull", "git push", "git add -A",
        "git commit -m", "git log --oneline", "git diff", "git clone", "git checkout", "git stash",
        "ssh", "ssh-keygen -t ed25519", "ssh-copy-id", "crontab -e", "crontab -l", "echo",
        "export", "env", "which", "whoami", "id", "date", "history", "openssl s_client -connect",
        "base64", "md5sum", "sha256sum",
    )
}
