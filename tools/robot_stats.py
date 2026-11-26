#!/usr/bin/env python3
"""CPU and memory statistics of the robot, to compare two versions of Toque.

A small sampler (tools/robot/stats.sh) runs on the robot as its own process; this script starts and
stops it over adb, brings its file back, and turns it into a report. No root, nothing in Toque.

    python tools/robot_stats.py start  [--ip 192.168.1.x] [--interval 5] [--hours 6]
    python tools/robot_stats.py mark   "veille"      # name the part of the scenario that begins now
    python tools/robot_stats.py status
    python tools/robot_stats.py stop   --out wuki60.txt
    python tools/robot_stats.py report wuki60.txt
    python tools/robot_stats.py compare wuki60.txt nous.txt

Python 3.8 or later, standard library only.
"""

import argparse
import os
import re
import shutil
import subprocess
import sys

REMOTE_DIR = "/data/local/tmp"
SCRIPT = REMOTE_DIR + "/toque-stats.sh"
DATA = REMOTE_DIR + "/toque-stats.txt"
MARKS = DATA + ".marks"
HZ = 100        # USER_HZ: the unit of the times in /proc, always 100 on Android
PAGE_KB = 4     # page size of the robot's ARM processor

# Order of the lines in the report.
SYSTEM = "Robot entier"
UI = "Toque : interface"
MACHINE = "Toque : machine"
OTHERS = ["system_server", "surfaceflinger", "mediaserver"]
SAMPLER = "mesure (cet outil)"


# ---------------------------------------------------------------- robot

def find_adb(given):
    candidates = [given] if given else [
        r"C:\Toque\platform-tools\adb.exe",
        os.path.expanduser("~/Toque/platform-tools/adb"),
        os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk", "platform-tools", "adb.exe"),
        shutil.which("adb") or "",
    ]
    for c in candidates:
        if c and os.path.isfile(c):
            return c
    sys.exit("adb not found: pass --adb <path>.")


class Robot:
    def __init__(self, adb, ip):
        self.adb = find_adb(adb)
        serial = ""
        if ip:
            serial = ip if re.search(r":\d+$", ip) else ip + ":5555"
            print(self._run(["connect", serial]))
        devices = [l.split("\t") for l in self._run(["devices"]).splitlines() if "\t" in l]
        if serial:
            devices = [d for d in devices if d[0] == serial]
        if len(devices) != 1 or devices[0][1].strip() != "device":
            sys.exit("adb must see exactly one robot, ready (adb devices). Over Wi-Fi: --ip <address>.")
        self.serial = devices[0][0]

    def _run(self, args):
        r = subprocess.run([self.adb] + args, capture_output=True)
        # Android 6 through adb on Windows ends lines with \r\r\n: drop every \r, so that no empty
        # line appears (text mode would turn each lone \r into a line of its own).
        return (r.stdout + r.stderr).decode("utf-8", "replace").replace("\r", "").strip()

    def shell(self, command):
        return self._run(["-s", self.serial, "shell", command])

    def push(self, local, remote):
        out = self._run(["-s", self.serial, "push", local, remote])
        if "error" in out.lower():
            sys.exit(out)

    def pull(self, remote, local):
        if os.path.exists(local):
            os.remove(local)
        out = self._run(["-s", self.serial, "pull", remote, local])
        if not os.path.exists(local):
            sys.exit(out)

    def running(self):
        """Pid of the sampler if it is running, else None."""
        out = self.shell(f"p=$(cat {DATA}.pid 2>/dev/null); [ -n \"$p\" ] && grep -q toque-stats /proc/$p/cmdline "
                         "&& echo RUN $p")
        m = re.search(r"RUN (\d+)", out)
        return m.group(1) if m else None


def start(robot, interval, hours):
    if robot.running():
        sys.exit("A measure is already running: stop it first (robot_stats.py stop).")
    local = os.path.join(os.path.dirname(os.path.abspath(__file__)), "robot", "stats.sh")
    with open(local, "rb") as f:
        if b"\r" in f.read():
            sys.exit(local + " has Windows line ends (CRLF): take the file from the repository again.")
    robot.push(local, SCRIPT)
    samples = max(1, int(hours * 3600 / interval))
    robot.shell(f"rm -f {DATA} {DATA}.stop {MARKS}")
    robot.shell(f"nohup sh {SCRIPT} {DATA} {interval} {samples} < /dev/null > /dev/null 2>&1 &")
    robot.shell("sleep 2")
    if not robot.running():
        sys.exit("The sampler did not start. Run it by hand to see why: "
                 f"adb shell sh {SCRIPT} {DATA} {interval} 2")
    print(f"Measure started: one sample every {interval} s, for {hours} h at most.")
    print("It goes on without the PC. Name each part with 'mark', end with 'stop --out <file>'.")


def mark(robot, name):
    if not robot.running():
        sys.exit("No measure is running.")
    clean = re.sub(r"[^\w .,()+-]", " ", name).strip()
    robot.shell(f"read -r up idle < /proc/uptime; echo \"$up {clean}\" >> {MARKS}")
    print("Mark: " + clean)


def status(robot):
    pid = robot.running()
    print(f"Measure running (pid {pid})." if pid else "No measure running.")
    print(robot.shell(f"ls -l {DATA} 2>/dev/null; grep -c '^@end' {DATA} 2>/dev/null; cat {MARKS} 2>/dev/null"))


def stop(robot, out):
    if robot.running():
        robot.shell(f"touch {DATA}.stop")
        robot.shell(f"n=0; while [ -e {DATA}.pid ] && [ $n -lt 90 ]; do sleep 1; n=$((n+1)); done")
        if robot.running():
            sys.exit("The sampler has not stopped yet: run 'stop' again in a minute.")
    robot.pull(DATA, out)
    marks = robot.shell(f"cat {MARKS} 2>/dev/null")
    if marks:
        with open(out, "a", encoding="utf-8", newline="\n") as f:
            for line in marks.splitlines():
                f.write("#mark " + line + "\n")
    print("Saved: " + out)
    report(out)


# ---------------------------------------------------------------- analysis

def parse(lines):
    """The file of the sampler: (header, samples, marks)."""
    head, samples, marks = {}, [], []
    s, section, pss_pid = None, None, None
    for raw in lines:
        line = raw.rstrip("\r\n")
        if line.startswith("#mark "):
            m = re.match(r"#mark ([\d.]+) (.*)", line)     # an empty mark line is ignored
            if m:
                marks.append((float(m.group(1)), m.group(2)))
        elif line.startswith("#"):
            k, _, v = line[1:].partition(" ")
            head[k] = v.strip()
        elif line.startswith("@S "):
            s = {"up": float(line.split()[2]), "cpu": None, "online": "", "freq": None,
                 "mem": {}, "procs": {}, "names": {}, "pss": {}}
            section = None
        elif s is None:
            continue
        elif line == "@end":
            samples.append(s)
            s = None
        elif line.startswith("@online"):
            s["online"] = line[8:].strip()
        elif line.startswith("@freq"):
            v = line[6:].strip()
            s["freq"] = int(v) if v.isdigit() else None
        elif line.startswith("@pss "):
            section, pss_pid = "pss", int(line[5:])
        elif line.startswith("@"):
            section = line[1:]
        elif section == "cpu" and line.startswith("cpu "):
            v = [int(x) for x in line.split()[1:]]
            # user nice system idle iowait irq softirq steal: everything but idle and iowait
            s["cpu"] = sum(v[:3]) + sum(v[5:8])
        elif section == "mem":
            m = re.match(r"(\w+):\s+(\d+)", line)
            if m:
                s["mem"][m.group(1)] = int(m.group(2))
        elif section == "proc":
            m = re.match(r"(\d+) \((.*)\) \S (.*)", line)
            if m:
                f = m.group(3).split()      # f[0] is field 4 (ppid) of proc(5)
                if len(f) >= 21:
                    # utime stime cutime cstime, start time, resident pages
                    s["procs"][int(m.group(1))] = (m.group(2), int(f[10]) + int(f[11]),
                                                  int(f[12]) + int(f[13]), int(f[18]), int(f[20]) * PAGE_KB)
        elif section == "ps":
            f = line.split()
            if len(f) >= 9 and f[1].isdigit():
                s["names"][int(f[1])] = f[-1]
        elif section == "pss":
            m = re.search(r"TOTAL:?\s+(\d+)", line)
            if m:
                s["pss"][pss_pid] = int(m.group(1))
    return head, samples, marks


def group_of(pid, comm, names, sampler):
    if pid == sampler:
        return SAMPLER
    name = names.get(pid, comm)
    if name == "app.toque":
        return UI
    if name == "app.toque:machine":
        return MACHINE
    base = name.rsplit("/", 1)[-1]
    return base if base in OTHERS else None


def intervals(head, samples):
    """One record per pair of samples: CPU in % of one core, memory in MB."""
    sampler = int(head.get("sampler", "0") or 0)
    names = {}
    out = []
    # A pid may get its name after its first sample: collect them all, keyed by (pid, start time).
    for s in samples:
        for pid, name in s["names"].items():
            if pid in s["procs"]:
                names[(pid, s["procs"][pid][3])] = name
    for a, b in zip(samples, samples[1:]):
        dt = b["up"] - a["up"]
        if dt <= 0:
            continue
        # The memory measure taken at the end of `a` runs during this interval, partly inside Toque.
        rec = {"up": b["up"], "dt": dt, "cpu": {}, "rss": {}, "pss": {}, "top": {}, "disturbed": bool(a["pss"])}
        known = {pid: names[(pid, p[3])] for pid, p in b["procs"].items() if (pid, p[3]) in names}
        for pid, (comm, own, children, begun, rss) in b["procs"].items():
            before = a["procs"].get(pid)
            same = before is not None and before[3] == begun
            # A process born between the two samples: all its time belongs to this interval.
            ticks = own - before[1] if same else (own if begun / HZ >= a["up"] else 0)
            g = group_of(pid, comm, known, sampler)
            if g == SAMPLER:
                # The sampler's cost is in the commands it waited for.
                ticks += children - before[2] if same else children
            percent = max(0, ticks) * 100.0 / HZ / dt
            label = SAMPLER if g == SAMPLER else known.get(pid, comm)
            rec["top"][label] = rec["top"].get(label, 0) + percent
            if g:
                rec["cpu"][g] = rec["cpu"].get(g, 0) + percent
                rec["rss"][g] = rec["rss"].get(g, 0) + rss / 1024.0
                if pid in b["pss"]:
                    rec["pss"][g] = rec["pss"].get(g, 0) + b["pss"][pid] / 1024.0
        if a["cpu"] is not None and b["cpu"] is not None:
            rec["cpu"][SYSTEM] = max(0, b["cpu"] - a["cpu"]) * 100.0 / HZ / dt
        mem = b["mem"]
        if "MemTotal" in mem:
            free = mem.get("MemAvailable", mem.get("MemFree", 0) + mem.get("Cached", 0))
            rec["rss"][SYSTEM] = (mem["MemTotal"] - free) / 1024.0
        rec["freq"] = b["freq"]
        out.append(rec)
    return out


def phases(records, marks):
    """[(name, records)]: the whole measure, then one entry per mark."""
    result = [("toute la mesure", records)]
    marks = sorted(marks)
    for i, (up, name) in enumerate(marks):
        end = marks[i + 1][0] if i + 1 < len(marks) else float("inf")
        # Only the intervals entirely inside the part.
        inside = [r for r in records if r["up"] - r["dt"] >= up and r["up"] <= end]
        if inside:
            if any(n == name for n, _ in result):
                result = [(n, rs + inside if n == name else rs) for n, rs in result]
            else:
                result.append((name, inside))
    return result


def percentile(values, q):
    v = sorted(values)
    return v[min(len(v) - 1, int(q * len(v)))]


def summarize(records):
    """{group: {cpu, cpu95, cpumax, rss, rssmax, pss}} for one part of the measure."""
    groups = [SYSTEM, UI, MACHINE] + OTHERS + [SAMPLER]
    quiet = [r for r in records if not r["disturbed"]] or records
    result = {}
    for g in groups:
        present = [r for r in records if g in r["cpu"] or g in r["rss"]]
        if not present:
            continue
        # CPU without the intervals the memory measure disturbed, except for the cost of the measure.
        # Mean over the whole part: a process that was absent used nothing.
        used = records if g == SAMPLER else quiet
        total = sum(r["dt"] for r in used)
        cpu = [r["cpu"].get(g, 0.0) for r in used]
        rss = [r["rss"][g] for r in present if g in r["rss"]]
        pss = [r["pss"][g] for r in present if g in r["pss"]]
        result[g] = {
            "cpu": sum(r["cpu"].get(g, 0.0) * r["dt"] for r in used) / total,
            "cpu95": percentile(cpu, 0.95),
            "cpumax": max(cpu),
            "rss": sum(rss) / len(rss) if rss else None,
            "rssmax": max(rss) if rss else None,
            "pss": sum(pss) / len(pss) if pss else None,
        }
    return result


def load(path):
    with open(path, encoding="utf-8", errors="replace") as f:
        head, samples, marks = parse(f)
    if len(samples) < 2:
        sys.exit(path + ": fewer than two complete samples.")
    return head, intervals(head, samples), marks


def num(v):
    return "-" if v is None else f"{v:.1f}"


def report(path):
    head, records, marks = load(path)
    print(f"\n{path}: Toque {head.get('toque') or '?'}, {head.get('model') or '?'}, "
          f"Android {head.get('android') or '?'}")
    print("CPU en % d'un cœur (400 % = les quatre cœurs). Mémoire en Mo ; pour le robot entier : mémoire occupée.")
    for name, rs in phases(records, marks):
        freq = [r["freq"] for r in rs if r["freq"]]
        extra = f", fréquence moyenne {sum(freq) / len(freq) / 1000:.0f} MHz" if freq else ""
        print(f"\n== {name} ({sum(r['dt'] for r in rs) / 60:.1f} min{extra})")
        print(f"{'':24}{'CPU moy':>9}{'CPU 95e':>9}{'CPU max':>9}{'RSS moy':>9}{'RSS max':>9}{'PSS moy':>9}")
        for g, s in summarize(rs).items():
            print(f"{g:24}" + "".join(f"{num(s[k]):>9}" for k in ("cpu", "cpu95", "cpumax", "rss", "rssmax", "pss")))
        total = sum(r["dt"] for r in rs)
        top = {}
        for r in rs:
            for label, p in r["top"].items():
                top[label] = top.get(label, 0) + p * r["dt"] / total
        best = sorted(top.items(), key=lambda kv: -kv[1])[:6]
        print("Plus gourmands : " + ", ".join(f"{label} {p:.1f} %" for label, p in best))


def compare(first, second):
    a, b = load(first), load(second)
    print(f"A = {first} (Toque {a[0].get('toque') or '?'})")
    print(f"B = {second} (Toque {b[0].get('toque') or '?'})")
    print("CPU moyen en % d'un cœur ; mémoire moyenne en Mo (PSS pour Toque, mémoire occupée pour le robot).")
    other = dict(phases(b[1], b[2]))
    for name, rs in phases(a[1], a[2]):
        if name not in other:
            print(f"\n== {name} : absent de B")
            continue
        sa, sb = summarize(rs), summarize(other[name])
        print(f"\n== {name} (A {sum(r['dt'] for r in rs) / 60:.1f} min, "
              f"B {sum(r['dt'] for r in other[name]) / 60:.1f} min)")
        print(f"{'':24}{'CPU A':>9}{'CPU B':>9}{'B - A':>9}{'Mém. A':>9}{'Mém. B':>9}{'B - A':>9}")
        for g in sa:
            if g not in sb:
                continue
            key = "rss" if g == SYSTEM else "pss"
            if sa[g][key] is None or sb[g][key] is None:
                key = "rss"
            ca, cb, ma, mb = sa[g]["cpu"], sb[g]["cpu"], sa[g][key], sb[g][key]
            diff = "-" if ma is None or mb is None else f"{mb - ma:+.1f}"
            print(f"{g:24}{num(ca):>9}{num(cb):>9}{cb - ca:>+9.1f}{num(ma):>9}{num(mb):>9}{diff:>9}")
    for name in other:
        if name not in dict(phases(a[1], a[2])):
            print(f"\n== {name} : absent de A")


# ---------------------------------------------------------------- command line

def main():
    for stream in (sys.stdout, sys.stderr):
        stream.reconfigure(encoding="utf-8", errors="replace")
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("--ip", help="address of the robot (ADB over Wi-Fi)")
    common.add_argument("--adb", help="path of adb")
    sub = p.add_subparsers(dest="command", required=True)
    s = sub.add_parser("start", parents=[common])
    s.add_argument("--interval", type=int, default=5)
    s.add_argument("--hours", type=float, default=6)
    sub.add_parser("mark", parents=[common]).add_argument("name")
    sub.add_parser("status", parents=[common])
    sub.add_parser("stop", parents=[common]).add_argument("--out", required=True)
    sub.add_parser("report").add_argument("file")
    c = sub.add_parser("compare")
    c.add_argument("first")
    c.add_argument("second")
    a = p.parse_args()
    if a.command == "report":
        report(a.file)
    elif a.command == "compare":
        compare(a.first, a.second)
    else:
        if a.command == "start" and (a.interval < 1 or a.hours <= 0):
            sys.exit("--interval and --hours must be positive.")
        robot = Robot(a.adb, a.ip)
        if a.command == "start":
            start(robot, a.interval, a.hours)
        elif a.command == "mark":
            mark(robot, a.name)
        elif a.command == "status":
            status(robot)
        else:
            stop(robot, a.out)


if __name__ == "__main__":
    main()
