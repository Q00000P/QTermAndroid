package org.qterm.android.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Разбор вывода монитора: GNU-сервер и BusyBox (Кинетик/Entware), CPU/сеть дельтами. */
class MonitorParserTest {

    private fun out(
        stat: String,
        rx: Long = 1000,
        tx: Long = 2000,
        who: String = "",
        df: String = GNU_DF,
        mounts: String = "/dev/vda1 / ext4 rw,relatime 0 0",
        mem: String = GNU_MEM,
    ) = """
        |@1
        |$stat
        |@2
        |$mem
        |@3
        |200000.55 380000.10
        |@4
        |Inter-|   Receive                                                |  Transmit
        | face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed
        |    lo: 999999 10 0 0 0 0 0 0 999999 10 0 0 0 0 0 0
        |  eth0: $rx 10 0 0 0 0 0 0 $tx 10 0 0 0 0 0 0
        |@5
        |$df
        |@6
        |$who
        |@7
        |nl-node
        |@8
        |0.42 0.30 0.25 1/123 4567
        |@9
        |2
        |@10
        |x86_64
        |@11
        |Debian GNU/Linux 12 (bookworm)
        |@12
        |47000
        |@13
        |$mounts
        |""".trimMargin()

    @Test fun firstSampleHasNoCpu() {
        val p = MonitorParser()
        val s = p.parse(out("cpu  100 0 100 800 0 0 0 0 0 0"))!!
        assertNull(s.cpu)
        assertTrue(s.history.isEmpty())
    }

    @Test fun cpuDelta() {
        val p = MonitorParser()
        p.parse(out("cpu  100 0 100 800 0 0 0 0 0 0"), nowMs = 0)
        // +100 busy (user), +100 idle → 50%
        val s = p.parse(out("cpu  200 0 100 900 0 0 0 0 0 0"), nowMs = 3000)!!
        assertEquals(50, s.cpu)
        assertEquals(listOf(50), s.history)
        // iowait считается простоем: +300 iowait, +100 user → 25%
        val s2 = p.parse(out("cpu  300 0 100 900 300 0 0 0 0 0"), nowMs = 6000)!!
        assertEquals(25, s2.cpu)
        assertEquals(listOf(50, 25), s2.history)
    }

    @Test fun cpuCounterResetIgnored() {
        val p = MonitorParser()
        p.parse(out("cpu  1000 0 1000 8000 0 0 0"))
        val s = p.parse(out("cpu  10 0 10 80 0 0 0"))!!
        assertNull(s.cpu) // ребут/переполнение — без мусорного значения
    }

    @Test fun historyCapped() {
        val p = MonitorParser()
        var busy = 0L
        var idle = 0L
        repeat(MonitorParser.HISTORY + 10) {
            busy += 10; idle += 10
            p.parse(out("cpu  $busy 0 0 $idle 0 0 0"))
        }
        val s = p.parse(out("cpu  ${busy + 10} 0 0 ${idle + 10} 0 0 0"))!!
        assertEquals(MonitorParser.HISTORY, s.history.size)
    }

    @Test fun memSwapLoadUptime() {
        val s = MonitorParser().parse(out("cpu  1 0 1 8 0 0 0"))!!
        assertEquals(3906L, s.memTotalMb)
        assertEquals(976L, s.memUsedMb)
        assertEquals(1023L, s.swapTotalMb)
        assertEquals(511L, s.swapUsedMb)
        assertEquals("0.42 0.30 0.25", s.load)
        assertEquals("0.42", s.load1)
        assertEquals("2д 7ч", s.uptime)
        assertEquals("nl-node", s.host)
        assertEquals(2, s.cores)
        assertEquals("x86_64", s.arch)
        assertEquals("Debian GNU/Linux 12 (bookworm)", s.os)
        assertEquals(47.0, s.tempC!!, 0.01)
    }

    @Test fun memNoAvailableFallsBackToFree() {
        val mem = "MemTotal:        262144 kB\nMemFree:          65536 kB"
        val s = MonitorParser().parse(out("cpu  1 0 1 8 0 0 0", mem = mem))!!
        assertEquals(256L, s.memTotalMb)
        assertEquals(192L, s.memUsedMb)
        assertEquals(0L, s.swapTotalMb)
    }

    @Test fun netDeltaSkipsLo() {
        val p = MonitorParser()
        p.parse(out("cpu  1 0 1 8 0 0 0", rx = 0, tx = 0), nowMs = 0)
        val s = p.parse(out("cpu  2 0 1 9 0 0 0", rx = 1_000_000, tx = 500_000), nowMs = 2_000)!!
        assertEquals(4.0, s.rxMbps, 0.001)
        assertEquals(2.0, s.txMbps, 0.001)
    }

    @Test fun gnuDiskAndWho() {
        val who = "root     pts/0        2026-10-22 10:00 (192.168.1.5)\nq        pts/1        2026-10-22 11:12 (tmux(1234).%0)\nroot     tty1         2026-10-20 09:00"
        val s = MonitorParser().parse(out("cpu  1 0 1 8 0 0 0", who = who))!!
        assertEquals(listOf("/", "/boot"), s.disks.map { it.mount })
        val root = s.rootDisk!!
        assertEquals("/", root.mount)
        assertEquals(42, root.pct)
        assertEquals(20511312L, root.totalKb)
        assertEquals(3, s.users.size)
        assertEquals(WhoEntry("root", "pts/0", "192.168.1.5"), s.users[0])
        assertEquals(WhoEntry("q", "pts/1", "tmux(1234).%0"), s.users[1])
        assertEquals(WhoEntry("root", "tty1", ""), s.users[2])
    }

    @Test fun busyboxKeenetic() {
        // Кинетик: корень — squashfs ro 100%, рабочий диск — /opt (Entware)
        val df = """
            Filesystem           1024-blocks    Used Available Capacity Mounted on
            /dev/root                  22784     22784         0 100% /
            tmpfs                      60416        88     60328   0% /tmp
            /dev/sda1                7740180   1032328   6297212  14% /opt
        """.trimIndent()
        val mounts = "/dev/root / squashfs ro,relatime 0 0\n/dev/sda1 /opt ext4 rw,noatime 0 0"
        val who = "USER       TTY      IDLE      TIME            HOST\nroot       pts/0    00:00     Oct 22 10:00:00 192.168.1.33"
        val mem = "MemTotal:         246788 kB\nMemFree:           31028 kB\nMemAvailable:      98764 kB\nSwapTotal:             0 kB\nSwapFree:              0 kB"
        val s = MonitorParser().parse(out("cpu 3456 0 2345 98765 12 0 34", df = df, mounts = mounts, who = who, mem = mem))!!
        assertEquals(listOf("/", "/opt"), s.disks.map { it.mount })
        assertTrue(s.disks.first { it.mount == "/" }.readOnly)
        assertEquals("/opt", s.rootDisk!!.mount)
        assertEquals(14, s.rootDisk!!.pct)
        assertEquals(listOf(WhoEntry("root", "pts/0", "192.168.1.33")), s.users)
        assertEquals(0L, s.swapTotalMb)
    }

    @Test fun busyboxRootfsDuplicateTakesLarger() {
        val df = """
            Filesystem           1024-blocks    Used Available Capacity Mounted on
            rootfs                         0         0         0   0% /
            /dev/root                1000000    500000    500000  50% /
        """.trimIndent()
        val s = MonitorParser().parse(out("cpu  1 0 1 8 0 0 0", df = df, mounts = ""))!!
        assertEquals(1, s.disks.size)
        assertEquals(50, s.disks[0].pct)
    }

    @Test fun crlfAndMissingSections() {
        val raw = "@1\r\ncpu  1 0 1 8 0 0 0\r\n@2\r\n@3\r\n@5\r\n@6\r\n"
        val s = MonitorParser().parse(raw)
        assertNotNull(s)
        assertEquals(0L, s!!.memTotalMb)
        assertTrue(s.disks.isEmpty())
        assertTrue(s.users.isEmpty())
        assertEquals("", s.uptime)
    }

    @Test fun garbageReturnsNull() {
        assertNull(MonitorParser().parse("sh: head: not found\n"))
    }

    @Test fun uptimeFormat() {
        assertEquals("0ч 5м", MonitorParser.fmtUptime(300))
        assertEquals("1д 0ч", MonitorParser.fmtUptime(86_400))
    }

    companion object {
        val GNU_MEM = """
            MemTotal:        4000000 kB
            MemFree:          500000 kB
            MemAvailable:    3000000 kB
            SwapTotal:       1048572 kB
            SwapFree:         524288 kB
        """.trimIndent()

        val GNU_DF = """
            Filesystem     1024-blocks    Used Available Capacity Mounted on
            udev               1987552       0   1987552       0% /dev
            /dev/vda1         20511312 8123456  11322948      42% /
            /dev/vda15          126678   11206    115472       9% /boot/efi
            /dev/vda2           999320  200000    730508      22% /boot
        """.trimIndent()
    }
}
