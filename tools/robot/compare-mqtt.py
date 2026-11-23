#!/usr/bin/env python3
"""Compare ce que publie MQTT avec l'API locale, pendant un même passage de la recette d'essai.

Deux enregistrements faits en même temps sur le même PC (leurs heures sont donc comparables) :

    python tools/robot/verifier-recette.py --host <ip robot> --token-file jeton.txt --capture api.jsonl
    python tools/robot/mqtt-capture.py --host <broker> --user <u> --topic "toque/<id>/#" --out mqtt.jsonl

    python tools/robot/compare-mqtt.py --api api.jsonl --mqtt mqtt.jsonl

Pour chaque message d'état MQTT, on cherche l'état de l'API (`/state`, une mesure par seconde) à la même heure et on
compare : le travail en cours (`jobActive` / `job`), la température demandée (`targetTempC`) et la vitesse
(`speedLevel` / `targetSpeed`). Les deux sources changent à quelques centaines de millisecondes d'écart : un message
est accepté s'il est d'accord avec l'un des états de l'API dans la fenêtre de ±TOLERANCE_S secondes ; les écarts
au-delà sont des différences réelles (valeur brute de la base, délai de publication…).

Code de sortie : 1 s'il y a au moins un écart réel, 0 sinon.
"""
import argparse
import bisect
import collections
import json
import sys

TOLERANCE_S = 2.0


def load_api(path):
    states, job_done = [], 0
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            e = json.loads(line)
            if e.get("name") == "state" and isinstance(e.get("data"), dict) and "ts" in e["data"]:
                states.append(e["data"])
            elif e.get("name") == "job_done":
                job_done += 1
    states.sort(key=lambda s: s["ts"])
    return states, job_done


def load_mqtt(path):
    rows, status = [], []
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            m = json.loads(line)
            if m["topic"].endswith("/state"):
                if not m.get("retain"):          # le message retenu de l'abonnement est un état ancien
                    rows.append((m["ts"], json.loads(m["payload"])))
            elif m["topic"].endswith("/status"):
                status.append((m["ts"], m["payload"]))
    rows.sort(key=lambda r: r[0])
    return rows, status


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--api", required=True, help="capture de verifier-recette.py (--capture)")
    ap.add_argument("--mqtt", required=True, help="capture de mqtt-capture.py")
    ap.add_argument("--tolerance", type=float, default=TOLERANCE_S, help="fenêtre de comparaison en secondes")
    a = ap.parse_args()
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except (AttributeError, ValueError):
        pass

    api, job_done = load_api(a.api)
    mqtt, status = load_mqtt(a.mqtt)
    if not api or not mqtt:
        print("FAIL  fichiers vides : aucun état %s" % ("API" if not api else "MQTT"))
        return 1
    ts = [s["ts"] for s in api]

    def window(t):
        lo = bisect.bisect_left(ts, t - a.tolerance)
        hi = bisect.bisect_right(ts, t + a.tolerance)
        return api[lo:hi]

    bad = collections.defaultdict(collections.Counter)
    seen = collections.Counter()
    for t, p in mqtt:
        w = window(t)
        if not w:
            seen["hors fenêtre de l'API"] += 1
            continue
        seen["comparés"] += 1
        if not any((s.get("job") is not None) == p["jobActive"] for s in w):
            bad["travail en cours"][("MQTT %s" % ("travail" if p["jobActive"] else "repos"),
                                     "API %s" % ("travail" if w[0].get("job") is not None else "repos"))] += 1
        if not any(s["machine"]["targetTempC"] == p["targetTempC"] for s in w):
            bad["température demandée"][(p["targetTempC"], tuple(sorted({s["machine"]["targetTempC"] for s in w})))] += 1
        if not any(s["machine"]["targetSpeed"] == p["speedLevel"] for s in w):
            bad["vitesse"][(p["speedLevel"], tuple(sorted({s["machine"]["targetSpeed"] for s in w})))] += 1

    n = seen["comparés"]
    t0, t1 = mqtt[0][0], mqtt[-1][0]
    jobs = [r for r in mqtt if r[1]["jobActive"]]
    print("INFO  messages d'état MQTT : %d en %.0f s (%d pendant un travail) ; états de l'API : %d ; job_done reçus : %d"
          % (len(mqtt), t1 - t0, len(jobs), len(api), job_done))
    if jobs:
        d = jobs[-1][0] - jobs[0][0]
        print("INFO  cadence pendant les travaux : un message toutes les %.1f s" % (d / max(1, len(jobs) - 1)))
    if status:
        print("INFO  statut MQTT : %s" % ", ".join(p for _, p in status))
    if seen["hors fenêtre de l'API"]:
        print("WARN  %d message(s) MQTT hors de la période enregistrée par l'API (non comparés)" % seen["hors fenêtre de l'API"])

    failures = 0
    for name in ("travail en cours", "température demandée", "vitesse"):
        c = bad[name]
        total = sum(c.values())
        if total:
            failures += 1
            def show(k):
                if isinstance(k[1], str):
                    return "%s / %s" % k
                return "MQTT %s / API %s" % (k[0], "ou ".join(str(x) + " " for x in k[1]).strip())
            detail = "; ".join("%s ×%d" % (show(k), v) for k, v in c.most_common(6))
            print("DIFF  %s : %d message(s) sur %d en désaccord avec l'API (±%.0f s) — %s" % (name, total, n, a.tolerance, detail))
        else:
            print("PASS  %s : les %d messages concordent avec l'API (±%.0f s)" % (name, n, a.tolerance))

    # Fin de travail : suite des états publiés et durée de l'état FINISHED.
    prev, seq = None, []
    for t, p in mqtt:
        key = (p["jobActive"], p["jobState"], p["endReason"])
        if key != prev:
            seq.append((t, key))
            prev = key
    fin = [(t, k) for t, k in seq if k[1] == "FINISHED"]
    after = {t: next((t2 for t2, k2 in seq if t2 > t and not k2[0]), None) for t, _ in fin}
    if fin:
        durs = [after[t] - t for t, _ in fin if after[t] is not None]
        print("INFO  fins de travail vues par MQTT : %d (état FINISHED visible %s s avant le retour au repos)"
              % (len(fin), "/".join("%.1f" % d for d in durs)))
        if len(fin) != job_done:
            failures += 1
            print("DIFF  fins de travail : %d vues par MQTT, %d événements job_done côté API" % (len(fin), job_done))
        else:
            print("PASS  fins de travail : autant que d'événements job_done côté API")
    elif job_done:
        failures += 1
        print("DIFF  fins de travail : aucune vue par MQTT, %d événements job_done côté API" % job_done)

    # Valeurs qui resteraient accrochées au repos
    idle = [p for _, p in mqtt if not p["jobActive"]]
    stale = [p for p in idle if p["targetTempC"] > 0 or p["speedLevel"] > 0]
    if stale:
        failures += 1
        print("DIFF  au repos : %d message(s) avec une température demandée ou une vitesse non nulle (écho de la base ?)"
              % len(stale))
    else:
        print("PASS  au repos : température demandée et vitesse à 0 sur %d message(s)" % len(idle))

    print("\n%s : %d différence(s) réelle(s) entre MQTT et l'API." % ("OK" if not failures else "ÉCART", failures))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
