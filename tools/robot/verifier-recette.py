#!/usr/bin/env python3
"""Capture et analyse un passage de la recette d'essai (tools/robot/recette-test.json).

Capture : `--capture FICHIER` écoute le flux d'événements (`/events`) et interroge `/diagnostics` chaque seconde,
puis écrit le tout dans un fichier (une ligne JSON par mesure). Ctrl+C (ou `--duration`) arrête la capture et lance
l'analyse. L'analyse lit aussi un simple journal SSE (sortie de `curl -N ... /events`, lignes `event:` / `data:`,
avec ou sans préfixe d'heure), mais sans `/diagnostics` elle ne voit pas ce que la base renvoie brut.

Pour chaque étape machine de la recette, le script contrôle ce que l'API a annoncé :
moteur, chauffe, consigne de température, niveau de vitesse, sens, poids illisible moteur en marche ;
puis les événements (`job_done`, `step`, `done`), la fraîcheur et la disparition de `job` à la fin.

    python tools/robot/verifier-recette.py --capture passage.jsonl --host 192.168.1.211 --token-file jeton.txt
    python tools/robot/verifier-recette.py passage.jsonl
    python tools/robot/verifier-recette.py passage.jsonl --save-reference reference-0.5.8.json
    python tools/robot/verifier-recette.py passage.jsonl --compare reference-0.5.8.json

Comparer deux versions : la référence garde, par étape, ce que la base renvoie brut (régime et vitesse mesurés, poids,
indicateur de chauffe, écho de la consigne, consigne après la fin du travail) et ce que l'API annonce. Un écart s'affiche
`DIFF` (ex. « la base remonte maintenant un régime »), ce qui peut être un progrès : c'est à lire, pas un échec.

Sans réseau ni dépendance (Python 3 seul). Code de sortie : 0 si tout passe (avertissements permis), 1 s'il y a un
échec, 2 si le journal est inutilisable. Les valeurs comparées sont des comportements (moteur en marche ? chauffe
demandée ?), pas des durées ni des degrés : la température de l'eau et les gestes varient d'un passage à l'autre.
"""
import argparse
import collections
import json
import os
import re
import sys
import threading
import time
import urllib.request

TEMP_LEVELS = [0, 37, 45, 50, 55, 60, 65, 70, 75, 80, 85, 90, 95, 100, 105, 110, 115, 120, 125, 130]
SKIP_S = 5            # la base signale le moteur et la chauffe quelques secondes après le départ
MAJORITY = 0.8
STALE_MS = 3000
HERE = os.path.dirname(os.path.abspath(__file__))

results = []          # (niveau, libellé, détail)


def note(level, label, detail=""):
    results.append((level, label, detail))


# --------------------------------------------------------------------------- lecture du journal

TS_PREFIX = re.compile(r"^\d\d:\d\d:\d\d ")


def read_events(path):
    events = []
    name = None
    with open(path, encoding="utf-8", errors="replace") as f:
        head = f.read(1)
        f.seek(0)
        if head == "{":                               # capture de ce script
            for raw in f:
                try:
                    rec = json.loads(raw)
                except ValueError:
                    continue
                events.append((rec["name"], rec["data"]))
            return events
        for raw in f:
            line = TS_PREFIX.sub("", raw.rstrip("\r\n"), count=1)
            if line.startswith("event:"):
                name = line[6:].strip()
            elif line.startswith("data:") and name:
                try:
                    events.append((name, json.loads(line[5:].strip())))
                except ValueError:
                    pass          # ligne coupée en fin de journal
                name = None
    return events


# --------------------------------------------------------------------------- capture

def capture(host, token, out_path, duration):
    base = f"http://{host}:8765/api/v1"
    lock = threading.Lock()
    stop = threading.Event()
    out = open(out_path, "w", encoding="utf-8")
    count = {"event": 0, "diag": 0}

    def write(name, data):
        with lock:
            out.write(json.dumps({"name": name, "data": data}, ensure_ascii=False) + "\n")
            out.flush()

    def get(path):
        req = urllib.request.Request(base + path, headers={"Authorization": f"Bearer {token}"})
        return urllib.request.urlopen(req, timeout=5)

    def poll():
        while not stop.is_set():
            try:
                with get("/diagnostics") as r:
                    write("diag", json.load(r))
                count["diag"] += 1
            except Exception as e:                        # noqa: BLE001
                print(f"diagnostics : {e}", file=sys.stderr)
            stop.wait(1.0)

    def listen():
        last = None
        while not stop.is_set():
            try:
                req = urllib.request.Request(base + "/events", headers={"Authorization": f"Bearer {token}"})
                if last:
                    req.add_header("Last-Event-ID", last)
                with urllib.request.urlopen(req, timeout=40) as r:
                    name = None
                    for raw in r:
                        if stop.is_set():
                            return
                        line = raw.decode("utf-8", "replace").rstrip("\r\n")
                        if line.startswith("id:"):
                            last = line[3:].strip()
                        elif line.startswith("event:"):
                            name = line[6:].strip()
                        elif line.startswith("data:") and name:
                            try:
                                write(name, json.loads(line[5:].strip()))
                                count["event"] += 1
                            except ValueError:
                                pass
                            name = None
            except Exception as e:                        # noqa: BLE001
                if not stop.is_set():
                    print(f"événements : {e} (nouvel essai dans 3 s)", file=sys.stderr)
                    stop.wait(3.0)

    threads = [threading.Thread(target=poll, daemon=True), threading.Thread(target=listen, daemon=True)]
    for t in threads:
        t.start()
    print(f"Capture dans {out_path} ({'Ctrl+C pour arrêter' if not duration else f'{duration} s'}).", file=sys.stderr)
    try:
        end = time.time() + duration if duration else None
        while end is None or time.time() < end:
            time.sleep(1)
    except KeyboardInterrupt:
        pass
    stop.set()
    out.close()
    print(f"{count['event']} événement(s), {count['diag']} mesure(s) de diagnostic.", file=sys.stderr)


# --------------------------------------------------------------------------- recette

def load_recipe(path):
    with open(path, encoding="utf-8") as f:
        r = json.load(f)["recipes"][0]
    steps = r["steps"]
    machine = {i: s["machine"] for i, s in enumerate(steps) if s.get("machine")}
    return r, steps, machine


def mode(values):
    return collections.Counter(values).most_common(1)[0][0] if values else None


def frac(values):
    values = list(values)
    return sum(1 for v in values if v) / len(values) if values else None


# --------------------------------------------------------------------------- analyse

def step_of(job):
    """Index (0-based) de l'étape de la recette à laquelle correspond cet instant du travail."""
    m = re.search(r"[ÉE]tapes? (\d+)", job.get("label", ""))
    if not m:
        return None
    return int(m.group(1)) - 1 + max(job.get("phase", 1) - 1, 0)


def analyse(events, recipe, steps, machine_steps):
    states = [d for n, d in events if n == "state"]
    if not states:
        note("FAIL", "journal", "aucun événement `state`")
        return None

    # --- échantillons par étape
    per = collections.defaultdict(list)
    t0 = {}
    for s in states:
        job = s.get("job")
        if not job:
            continue
        i = step_of(job)
        if i is None:
            continue
        t0.setdefault(i, s["ts"])
        per[i].append((s, s["ts"] - t0[i]))

    summary = {}
    for i, m in sorted(machine_steps.items()):
        label = f"étape {i + 1} ({m['seconds']} s, vitesse {m['speed']}, {m['tempC']} °C" \
                + (", inverse)" if m.get("reverse") else ")")
        samples = per.get(i, [])
        if not samples:
            note("FAIL", label, "jamais vue dans `job` (étape non lancée ?)")
            continue
        late = [s for s, dt in samples if dt >= SKIP_S and s["job"]["state"] == "RUNNING"]
        allj = [s for s, _ in samples]
        want_t = m["tempC"]

        # consigne : ce que Toque demande, dès le départ
        targets = [s["machine"]["targetTempC"] for s in allj]
        ok = all(t == want_t for t in targets)
        note("PASS" if ok else "FAIL", label + " · consigne", f"targetTempC {sorted(set(targets))}, attendu {want_t}")

        # sens
        rev = [s["job"].get("reverse", False) for s in allj]
        want_rev = bool(m.get("reverse"))
        note("PASS" if all(r == want_rev for r in rev) else "FAIL", label + " · sens",
             f"job.reverse {sorted(set(rev))}, attendu {want_rev}")

        if not late:
            note("WARN", label + " · mesures", "trop court : aucun instant après les "
                 f"{SKIP_S} premières secondes (moteur, chauffe, vitesse non contrôlés)")
            continue

        motor = frac(s["machine"]["motorOn"] for s in late)
        note("PASS" if motor >= MAJORITY else "FAIL", label + " · moteur", f"motorOn {motor:.0%} des instants")

        heat = frac(s["machine"]["heaterOn"] for s in late)
        if want_t > 0:
            note("PASS" if heat >= MAJORITY else "FAIL", label + " · chauffe", f"heaterOn {heat:.0%}, chauffe demandée")
        else:
            note("PASS" if heat == 0 else "FAIL", label + " · pas de chauffe",
                 f"heaterOn {heat:.0%}, aucune chauffe demandée")

        ts = [s["machine"]["targetSpeed"] for s in late]
        sp = frac(t == m["speed"] for t in ts)
        note("PASS" if sp >= MAJORITY else "FAIL", label + " · vitesse",
             f"targetSpeed {sorted(set(ts))}, attendu {m['speed']}")

        w = [s["machine"]["weightG"] for s in late if s["machine"]["motorOn"]]
        note("PASS" if all(x is None for x in w) else "FAIL", label + " · poids",
             "weightG illisible (null) moteur en marche" if all(x is None for x in w)
             else f"weightG {sorted(set(x for x in w if x is not None))[:3]} moteur en marche")

        if want_t > 0 and m["seconds"] >= 120:      # sur une étape courte la température n'a pas le temps de bouger
            temps = [s["machine"]["tempC"] for s in allj if s["machine"]["tempC"] > 0]
            if temps:
                reached = max(temps) >= want_t - 1
                note("INFO" if reached else "WARN", label + " · température",
                     f"{temps[0]} → {max(temps)} °C, consigne {want_t} °C "
                     + ("atteinte" if reached else "non atteinte dans le temps de l'étape"))
            else:
                note("INFO", label + " · température", "tempC resté à 0 (contenu sous 37 °C, la base ne mesure pas)")

        summary[str(i + 1)] = {
            "moteur": motor >= MAJORITY, "chauffe": heat >= MAJORITY, "consigne": mode(targets),
            "vitesse": mode(ts), "inverse": mode(rev), "poidsIllisible": all(x is None for x in w),
        }

    base = analyse_base(events, per, machine_steps)
    if base:
        summary["base"] = base

    # --- événements
    done = [d for n, d in events if n == "job_done"]
    aborted = [d for d in done if d.get("result") != "finished"]
    note("PASS" if done and not aborted else "FAIL", "job_done",
         f"{len(done)} reçu(s), {len(aborted)} arrêté(s)")
    # une série enchaînée = un travail : le nombre de job_done doit égaler le nombre de séries lancées
    series = sorted({re.sub(r"\s*·.*", "", s["job"]["label"]) for s in states if s.get("job")})
    note("PASS" if len(done) == len(series) else "WARN", "un job_done par travail",
         f"{len(done)} job_done pour {len(series)} travaux vus ({', '.join(series)})")
    chained = [d for d in done if re.search(r"Étapes \d+ à \d+", d.get("label", ""))]
    note("INFO", "enchaînement", f"{len(chained)} série(s) enchaînée(s) : "
         + (", ".join(re.sub(r"\s*·.*", "", d["label"]) for d in chained) or "aucune"))

    stepev = [d["index"] for n, d in events if n == "step"]
    want = [i for i in range(len(steps))]
    missing = [i + 1 for i in want if i not in stepev and i != 0]
    note("PASS" if not missing else "WARN", "événements step",
         "toutes les étapes annoncées" if not missing else f"étapes jamais annoncées : {missing}")
    ended = [d for n, d in events if n == "done"]
    note("PASS" if ended else "WARN", "événement done",
         ended[-1]["result"] if ended else "absent (recette laissée ouverte ou abandonnée ?)")

    # --- après chaque fin de travail : `job` disparaît, la consigne et la chauffe retombent
    bad = 0
    last_done = False
    for n, d in events:
        if n == "job_done":
            last_done = True
        elif n == "state" and last_done:
            last_done = False
            m_ = d["machine"]
            if d.get("job") is None and (m_["targetTempC"] != 0 or m_["heaterOn"]):
                bad += 1
    note("PASS" if bad == 0 else "FAIL", "fin de travail",
         "premier `state` après chaque `job_done` : job null, consigne 0, pas de chauffe" if bad == 0
         else f"{bad} cas où la consigne ou la chauffe sont restées après la fin")

    # --- fraîcheur et liaison
    ages = [s.get("snapshotAgeMs", -1) for s in states]
    worst = max(ages)
    note("PASS" if worst < STALE_MS else "FAIL", "fraîcheur", f"snapshotAgeMs maximal {worst} ms (limite {STALE_MS})")
    links = sorted({s["link"] for s in states})
    note("PASS" if links == ["READY"] else "FAIL", "liaison", f"link {links}")
    faults = [d for n, d in events if n == "fault" and d.get("active")]
    note("PASS" if not faults else "FAIL", "défauts", f"{len(faults)} défaut(s) actif(s)")

    return summary


DESC = {
    "rpmRemonte": "la base remonte un régime mesuré (rpm > 0) en marche",
    "vitesseMesureeRemonte": "la base remonte une vitesse mesurée (speed > 0) en marche",
    "poidsAZeroMoteurEnMarche": "la base renvoie un poids de 0 moteur en marche",
    "chauffeBrute": "la base annonce la chauffe (mode « moteur + chauffe »)",
    "echoConsigneEgaleDemande": "la consigne renvoyée par la base égale la température demandée",
    "echoConsigneRestee": "la base garde sa consigne après la fin du travail",
    "chauffeBruteRestee": "la base annonce encore la chauffe après la fin du travail",
    "moteurBrutRestee": "la base annonce encore le moteur après la fin du travail",
}


def analyse_base(events, per, machine_steps):
    """Ce que la base renvoie brut (`/diagnostics`), étape par étape, et ce que Toque lui envoie."""
    diags = [d for n, d in events if n == "diag"]
    if not diags:
        note("WARN", "base brute", "aucune mesure de /diagnostics dans ce journal : le comportement brut de la base "
             "n'est pas contrôlé (capturer avec --capture)")
        return None
    out = {}
    start = end = None
    for i, m in sorted(machine_steps.items()):
        samples = per.get(i)
        if not samples:
            continue
        t_first, t_last = samples[0][0]["ts"], samples[-1][0]["ts"]
        start = t_first if start is None else min(start, t_first)
        end = t_last if end is None else max(end, t_last)
        label = f"étape {i + 1} · base brute"
        late = [d for d in diags if t_first + SKIP_S <= d["ts"] <= t_last and d["job"]["ongoing"]
                and d["job"]["state"] == "RUNNING"]
        if not late:
            note("WARN", label, "aucune mesure de diagnostic après les premières secondes")
            continue
        b = [d["base"] for d in late]
        moving = [x for x in b if x["motorOn"]]
        echo = mode([x["targetTempC"] for x in b])
        entry = {
            "rpmRemonte": any(x["rpm"] > 0 for x in b),
            "vitesseMesureeRemonte": any(x["speed"] > 0 for x in b),
            "poidsAZeroMoteurEnMarche": (all(x["weightG"] == 0 for x in moving) if moving else None),
            "chauffeBrute": frac(x["heaterOn"] for x in b) >= MAJORITY,
            "echoConsigneEgaleDemande": echo == m["tempC"],
        }
        out[str(i + 1)] = entry
        note("INFO", label, f"rpm {'remonte' if entry['rpmRemonte'] else '0'}, vitesse mesurée "
             f"{'remonte' if entry['vitesseMesureeRemonte'] else '0'}, poids {sorted(set(x['weightG'] for x in moving))[:3]} "
             f"moteur en marche, indicateur de chauffe {'oui' if entry['chauffeBrute'] else 'non'}, "
             f"consigne renvoyée {echo} (demandé {m['tempC']})")
        # ce que Toque envoie lui-même : doit toujours correspondre à l'étape
        sent_ok = frac(d["sent"]["speed"] == m["speed"] and d["sent"]["operation"] == "STANDARD"
                       and d["sent"]["tempLevel"] == TEMP_LEVELS.index(m["tempC"]) for d in late)
        note("PASS" if sent_ok >= MAJORITY else "FAIL", label + " · envoyé",
             f"{sent_ok:.0%} des trames envoyées ont la vitesse {m['speed']} et {m['tempC']} °C")
    if start is not None:
        gaps = [d for d in diags if start < d["ts"] < end and not d["job"]["ongoing"]]
        if gaps:
            out["apresFin"] = {
                "echoConsigneRestee": frac(d["base"]["targetTempC"] > 0 for d in gaps) >= 0.5,
                "chauffeBruteRestee": frac(d["base"]["heaterOn"] for d in gaps) >= 0.5,
                "moteurBrutRestee": frac(d["base"]["motorOn"] for d in gaps) >= 0.5,
            }
            a = out["apresFin"]
            note("INFO", "base brute · entre les travaux",
                 ", ".join(f"{DESC[k]} : {'oui' if v else 'non'}" for k, v in a.items()))
    return out


# --------------------------------------------------------------------------- informations du robot

def robot_info(host):
    try:
        with urllib.request.urlopen(f"http://{host}:8765/api/v1/info", timeout=5) as r:
            info = json.load(r)
    except Exception as e:                       # noqa: BLE001 : on veut tout dire à l'utilisateur
        note("WARN", "version du robot", f"illisible ({e})")
        return {}
    note("INFO", "version du robot", f"Toque {info['toque']['version']} (build {info['toque']['build']}), "
         f"base firmware {info['board']['firmware']}, Android {info['android']['release']}, modèle {info['model']}")
    return {"toque": info["toque"]["version"], "build": info["toque"]["build"],
            "firmware": info["board"]["firmware"], "android": info["android"]["release"]}


def compare(summary, ref_path):
    with open(ref_path, encoding="utf-8") as f:
        ref = json.load(f)
    old = dict(ref.get("steps", {}))
    new = dict(summary)
    old_base, new_base = old.pop("base", None), new.pop("base", None)
    diffs = 0
    for k in sorted(set(old) | set(new), key=int):
        a, b = old.get(k), new.get(k)
        if a is None or b is None:
            note("WARN", f"référence · étape {k}", "absente d'un des deux passages")
            diffs += 1
            continue
        d = {key: (a.get(key), b.get(key)) for key in a if a.get(key) != b.get(key)}
        if d:
            diffs += 1
            note("FAIL", f"référence · étape {k}", ", ".join(f"{key}: {x} → {y}" for key, (x, y) in d.items()))

    def oui(v):
        return "?" if v is None else "oui" if v else "non"

    if old_base is None or new_base is None:
        note("WARN", "référence · base brute", "absente d'un des deux passages (capturer avec --capture)")
    else:
        groups = {}                      # un même écart sur plusieurs étapes n'est dit qu'une fois
        for k in sorted(set(old_base) | set(new_base), key=lambda x: (x == "apresFin", int(x) if x.isdigit() else 0)):
            a, b = old_base.get(k), new_base.get(k)
            if a is None or b is None:
                continue
            for key in a:
                if a.get(key) != b.get(key):
                    groups.setdefault((key, a.get(key), b.get(key)), []).append(k)
        for (key, x, y), where in groups.items():
            diffs += 1
            steps_ = [w for w in where if w != "apresFin"]
            label = ("étapes " + ", ".join(steps_)) if steps_ else "entre les travaux"
            note("DIFF", f"base brute · {label}", f"{DESC.get(key, key)} : {oui(x)} → {oui(y)}")
    if not diffs:
        meta = ref.get("robot", {})
        note("PASS", "référence", "même comportement que la référence"
             + (f" ({meta.get('toque', '?')}, firmware {meta.get('firmware', '?')})" if meta else ""))


def main():
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8")      # console Windows en cp1252 par défaut
        except (AttributeError, ValueError):
            pass
    ap =argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("journal", nargs="?", help="capture ou journal SSE d'un passage de la recette")
    ap.add_argument("--capture", metavar="FICHIER", help="capture un passage (événements + /diagnostics) puis l'analyse")
    ap.add_argument("--token", help="jeton de l'API (ou variable TOQUE_TOKEN)")
    ap.add_argument("--token-file", help="fichier contenant le jeton")
    ap.add_argument("--duration", type=int, default=0, help="durée de la capture en secondes (défaut : Ctrl+C)")
    ap.add_argument("--recipe", default=os.path.join(HERE, "recette-test.json"))
    ap.add_argument("--host", help="adresse du robot : ajoute sa version et son firmware au rapport")
    ap.add_argument("--save-reference", metavar="FICHIER", help="enregistre le comportement observé comme référence")
    ap.add_argument("--compare", metavar="FICHIER", help="compare au comportement d'une référence enregistrée")
    a = ap.parse_args()

    if a.capture:
        token = a.token or os.environ.get("TOQUE_TOKEN") or (open(a.token_file).read().strip() if a.token_file else "")
        if not a.host or not token:
            print("--capture demande --host et un jeton (--token, --token-file ou TOQUE_TOKEN).", file=sys.stderr)
            return 2
        capture(a.host, token, a.capture, a.duration)
        a.journal = a.capture
    if not a.journal:
        ap.print_usage(sys.stderr)
        return 2
    try:
        events = read_events(a.journal)
        recipe, steps, machine_steps = load_recipe(a.recipe)
    except OSError as e:
        print(f"Illisible : {e}", file=sys.stderr)
        return 2
    robot = robot_info(a.host) if a.host else {}
    summary = analyse(events, recipe, steps, machine_steps)
    if summary is None:
        pass
    else:
        if a.compare:
            compare(summary, a.compare)
        if a.save_reference:
            with open(a.save_reference, "w", encoding="utf-8") as f:
                json.dump({"recipe": recipe["id"], "robot": robot, "steps": summary}, f, indent=2, ensure_ascii=False)
            note("INFO", "référence", f"enregistrée dans {a.save_reference}")

    width = max(len(label) for _, label, _ in results)
    for level, label, detail in results:
        print(f"{level:<4}  {label:<{width}}  {detail}")
    fails = sum(1 for lv, _, _ in results if lv == "FAIL")
    warns = sum(1 for lv, _, _ in results if lv == "WARN")
    diffs = sum(1 for lv, _, _ in results if lv == "DIFF")
    print(f"\n{'ÉCHEC' if fails else 'OK'} : {fails} échec(s), {warns} avertissement(s), "
          f"{sum(1 for lv, _, _ in results if lv == 'PASS')} contrôle(s) réussi(s)"
          + (f", {diffs} écart(s) de comportement de la base avec la référence (à lire)." if diffs else "."))
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
