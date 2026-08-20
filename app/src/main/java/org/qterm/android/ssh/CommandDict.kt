package org.qterm.android.ssh

/**
 * Базовый словарь подсказок (как в Termius): частые админские команды
 * с уклоном в Debian/systemd/docker/сети/Keenetic. Персональный журнал
 * (vault.cmdHistory) имеет приоритет и синкается между устройствами.
 */
object CommandDict {
    val COMMON: List<String> = listOf(
        // система
        "systemctl status", "systemctl restart", "systemctl stop", "systemctl start",
        "systemctl enable --now", "systemctl disable", "systemctl daemon-reload",
        "journalctl -u", "journalctl -f", "journalctl -xe", "dmesg | tail -50",
        "uname -a", "uptime", "free -h", "df -h", "du -sh *", "lsblk", "mount",
        "htop", "top", "ps aux | grep", "kill -9", "pkill -f", "nohup",
        "crontab -e", "crontab -l", "reboot", "shutdown -h now",
        "chmod +x", "chmod 600", "chmod 755", "chown -R", "ln -s",
        "tail -f", "tail -n 100", "less", "cat", "nano", "vi",
        "grep -r", "grep -i", "find / -name", "find . -type f -name",
        "sed -i", "awk '{print $1}'", "sort | uniq -c | sort -rn", "wc -l",
        "tar xzf", "tar czf", "unzip", "zip -r", "rsync -avz --progress",
        "scp", "ssh", "ssh-keygen -t ed25519", "ssh-copy-id",
        "history | grep", "watch -n 1", "date", "timedatectl",
        // пакеты
        "apt update", "apt upgrade -y", "apt install -y", "apt remove",
        "apt autoremove -y", "apt search", "apt list --installed | grep",
        "dpkg -l | grep", "dpkg -i", "apt-cache policy",
        "opkg update", "opkg install", "opkg remove", "opkg list-installed | grep",
        // сеть
        "ip a", "ip r", "ip -br a", "ip route get", "ip link set",
        "ss -tulpn", "ss -s", "netstat -tulpn", "ping -c 4", "traceroute", "mtr",
        "curl -s", "curl -I", "curl -o", "curl ifconfig.me", "wget",
        "dig", "dig +short", "nslookup", "host", "whois",
        "iptables -L -n -v", "iptables -t nat -L -n", "nft list ruleset",
        "ufw status", "ufw allow", "tcpdump -i any -n port",
        "ethtool", "arp -a", "hostname -I",
        // wireguard / xray / прокси
        "wg show", "wg-quick up", "wg-quick down", "wg genkey", "wg pubkey",
        "systemctl restart xray", "systemctl status xray", "journalctl -u xray -f",
        "systemctl restart x-ui", "x-ui", "xray version", "xray run -test -config",
        "systemctl restart AdGuardHome", "systemctl status AdGuardHome",
        "unbound-checkconf", "systemctl restart unbound",
        "nginx -t", "systemctl reload nginx", "certbot renew --dry-run",
        // docker
        "docker ps", "docker ps -a", "docker logs -f", "docker logs --tail 100",
        "docker restart", "docker stop", "docker rm -f", "docker images",
        "docker exec -it", "docker compose up -d", "docker compose down",
        "docker compose pull", "docker compose logs -f", "docker system prune -af",
        "docker stats", "docker inspect",
        // git
        "git status", "git pull", "git push", "git add -A", "git commit -m",
        "git log --oneline -10", "git diff", "git checkout", "git clone",
        "git stash", "git reset --hard",
        // файлы/диагностика
        "mkdir -p", "cp -r", "mv", "rm -rf", "touch", "stat", "file",
        "openssl s_client -connect", "openssl x509 -noout -dates -in",
        "base64", "md5sum", "sha256sum", "dd if=", "lsof -i", "strace -p",
        "ulimit -n", "sysctl -w", "sysctl net.ipv4.ip_forward=1",
        "echo 1 > /proc/sys/net/ipv4/ip_forward",
    )
}
