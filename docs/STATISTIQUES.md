# Mesurer le CPU et la mémoire sur le robot

`tools/robot_stats.py` mesure l'occupation du robot, par exemple pour comparer deux versions de Toque. La mesure est
faite par un petit script ([`tools/robot/stats.sh`](../tools/robot/stats.sh)) qui tourne sur le robot **dans son propre
processus**, hors de Toque : rien n'est ajouté à l'application, qui n'a donc pas besoin de connaître cet outil. Il ne
demande pas le root et n'envoie rien au bloc moteur.

Il faut Python 3.8 ou plus récent sur le PC, et adb relié au robot (câble ou Wi-Fi, avec `--ip`).

## Une mesure

```powershell
python tools\robot_stats.py start --ip 192.168.1.50
```

Puis, au début de chaque partie du scénario :

```powershell
python tools\robot_stats.py mark "accueil" --ip 192.168.1.50
```

Et à la fin, ce qui rapporte le fichier sur le PC et affiche le rapport :

```powershell
python tools\robot_stats.py stop --out nous.txt --ip 192.168.1.50
```

La mesure continue sans le PC : le câble peut être débranché entre deux commandes. Elle s'arrête seule après 6 h
(`--hours`), et un redémarrage du robot l'interrompt (le fichier reste, `stop` le rapporte).

## Comparer deux versions

```powershell
python tools\robot_stats.py compare wuki60.txt nous.txt
```

Pour que la comparaison ait un sens :

- **Même base.** Notre 0.10.0.1 se compare à la 0.10.0 de Wuki60 : l'écart vient alors de nos ajouts. Une comparaison
  avec une version plus récente mélange nos ajouts et ses changements.
- **Même scénario, mêmes noms de parties**, durées proches. Par exemple : `accueil` (10 min sans toucher), `veille`
  (10 min), `manuel` (5 min, bol vide, mêmes réglages), `recette` (la même recette guidée), `synchro`.
- **Mêmes réglages** : API Home Assistant activée ou non (la mesurer à part), téléphone appairé ou non, Wi-Fi.
- Attendre quelques minutes après un démarrage ou une installation : Android travaille encore.

### Passer d'une version à l'autre

Les deux projets signent leurs APK avec la même clé (`app/signature/toque.p12`) et le même nom de paquet : l'un
s'installe à la place de l'autre et les réglages restent. Mais le numéro interne (`versionCode`) de la 0.10.0 de
Wuki60 est 18, le nôtre 23 : Android refuse de revenir en arrière, et `tools\install-robot.ps1` s'arrête alors sans
rien changer. Le plus sûr est de compiler la 0.10.0 depuis l'étiquette `v0.10.0` avec un `versionCode` plus grand que
celui installé (`app/build.gradle.kts`), puis de revenir à notre version de la même façon. Ne pas désinstaller Toque :
ses réglages et les recettes personnelles seraient perdus. Une fois la version de Wuki60 installée, ne pas lancer la
mise à jour depuis *Réglages › Mises à jour* avant la fin de la mesure.

## Lire le rapport

| Colonne | Sens |
|---|---|
| CPU | En % **d'un cœur** : 100 % = un cœur occupé, 400 % = les quatre. Moyenne, 95e centile, maximum. |
| RSS | Mémoire résidente, pages partagées comprises (surestime chaque processus). |
| PSS | Mémoire d'un processus de Toque, pages partagées réparties : la bonne valeur pour comparer. Une par minute. |

- La ligne *Robot entier* compte tout, y compris les commandes que Toque lance en root pour la liaison série, qui
  n'apparaissent pas dans les lignes *Toque*. Les processus les plus gourmands sont listés sous chaque tableau.
- La fréquence du processeur varie : à travail égal, le pourcentage monte quand elle baisse. Elle est affichée, à
  comparer d'une mesure à l'autre.
- La mesure de la PSS fait travailler Toque : les intervalles concernés sont écartés des chiffres de CPU.
- Le coût de l'outil lui-même est la ligne *mesure*. Une partie du coût de la PSS tombe aussi dans `system_server`,
  écarté de la même façon.
- Un fichier de mesure pèse environ 25 Mo par heure à un échantillon toutes les 5 s.

## Si adb reste `offline`

L'intégration *Android Debug Bridge* de Home Assistant, si elle vise le robot, occupe son service adb : le PC se
connecte au port 5555 mais le robot reste `offline`. La désactiver dans Home Assistant le temps des mesures (elle
fausserait aussi les chiffres en interrogeant le robot). L'intégration
[toque-homeassistant](https://github.com/McSoda/toque-homeassistant) n'utilise pas adb.
