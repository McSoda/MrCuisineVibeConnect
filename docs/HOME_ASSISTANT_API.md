# API locale de Toque pour Home Assistant (spécification, v1)

Statut : **proposition, rien n'est implémenté**. Décisions prises : transport HTTP + SSE avec annonce mDNS,
**lecture seule**, intégration HA dans un **dépôt séparé** (`toque-homeassistant`, domaine `toque`, distribuée par HACS).

## 1. Principes

- Le serveur vit dans le **processus principal** de Toque, rattaché à `ToqueApp` (pas à l'activité : il continue quand
  l'écran change). Le service machine tourne dans un autre processus et ne connaît ni les recettes, ni la session de
  cuisson, ni les préférences ; le serveur lit l'état de la machine par `MachineClient`, comme l'interface.
- Aucune dépendance : `ServerSocket` + JSON maison (`core/json`). Un fil d'écoute, 8 connexions simultanées au plus
  (au-delà : `503`), une réponse par connexion (`Connection: close`), sauf le flux SSE.
- Le code est dans `core` (routage, JSON, cadence, serveur : testables sans Android) ; seule la liaison avec l'appli
  (recettes, préférences, mDNS) est dans `app`.
- **Lecture seule** : toute méthode autre que `GET` renvoie `405`. Aucun endpoint ne touche au moteur,
  à la chauffe ou à la session. Les règles de `Safety.kt` ne sont donc pas concernées.
- Désactivé par défaut. Réglages › Intégrations › Home Assistant : interrupteur, port, jeton.
- Version d'API entière (`api`). Évolutions **additives** seulement dans une version ; un champ inconnu est ignoré.

## 2. Découverte et accès

- Service mDNS `_toque._tcp`, port par défaut **8765**. Enregistrements TXT : `api=1`, `id=<id stable>`,
  `version=<version Toque>`, `name=<nom du robot>`.
- Base : `http://<ip>:8765/api/v1`.
- `GET /info` est public (sert à la découverte et à valider l'adresse). Tout le reste exige
  `Authorization: Bearer <jeton>`.
- Jeton : 32 octets aléatoires générés à l'activation, affichés sur l'écran du robot, régénérables.
  Il est saisi une fois dans le config flow HA.
- HTTP en clair sur le réseau local : limite assumée, comme ADB sur 5555. Le jeton ne protège pas contre
  l'écoute du réseau, seulement contre un accès fortuit. À documenter.
- Erreurs : JSON `{"error":"<code>","message":"…"}` avec `401`, `404`, `405`, `503` (machine ou base non prête).

## 3. Endpoints

### `GET /info`
```json
{
  "api": 1, "id": "toque-3f9a…", "name": "Monsieur Cuisine",
  "model": "SKMC 1200",
  "toque": {"version": "0.5.5", "build": 55},
  "board": {"firmware": 0},
  "android": {"release": "6.0", "sdk": 23},
  "ip": "192.168.1.40", "uptimeS": 86400,
  "lang": "fr", "recipeCount": 312, "recipesRev": 7
}
```

### `GET /state`
Instantané complet. `status` est calculé côté robot :
`fault` (défaut actif) › `recipe` (session guidée active) › `running` (job ou Turbo actif, en pause compris) › `standby`.
L'état « indisponible » n'existe pas côté robot : HA le déduit quand le robot ne répond plus.
```json
{
  "ts": 1763700000, "status": "recipe", "screenOn": true,
  "link": "READY", "fault": null,
  "machine": {
    "tempC": 82, "targetTempC": 90, "rpm": 1200, "speed": 4, "weightG": 350, "scaleStable": true,
    "lidOpen": false, "bowlDetected": true, "motorOn": true, "heaterOn": true
  },
  "job": {
    "kind": "MANUAL", "label": "Étape 3", "state": "RUNNING", "pauseReason": "",
    "phase": 1, "phaseCount": 1, "elapsedS": 40, "totalS": 300, "remainingS": 260,
    "speed": 4, "reverse": false, "accessory": "BLADE"
  },
  "sessionId": "c1d2…"
}
```
`job` vaut `null` sans travail en cours ; `fault` vaut `{"code":"…","message":"…"}` en cas de défaut ;
`sessionId` vaut `null` hors recette. `screenOn` : état de l'écran Android (« veille » côté utilisateur
= `status: standby`, combiné à `screenOn` ; voir §7).

### `GET /session`
`{"active": false}` hors recette. Sinon :
```json
{
  "active": true, "id": "c1d2…", "rev": 12,
  "recipe": {"id": "veloute-de-potiron", "title": "Velouté de potiron", "category": "Soupe", "servings": 4},
  "stepIndex": 2, "stepCount": 8,
  "awaiting": "user",
  "step": {"index": 2, "kind": "manual", "text": "Ajouter la crème.", "machine": null,
           "weigh": {"label": "Crème", "grams": 200}},
  "lastManual": {"index": 0, "text": "Éplucher et couper le potiron."},
  "nextManual": {"index": 5, "text": "Verser dans un bol."},
  "ingredients": [{"group": "", "items": [{"qty": "800", "unit": "g", "name": "potiron", "note": ""}]}],
  "startedAt": 1763699000
}
```
- Étape **manuelle** = étape sans réglage machine (`Step.machine == null`) : ajout, pesée, geste.
  Une étape avec réglage est `kind: "machine"` (`machine` contient `program`, `seconds`, `speed`, `tempC`,
  `reverse`, `accessory`, `pulses`).
- `lastManual` : plus grand index manuel **strictement inférieur** à `stepIndex`.
  `nextManual` : plus petit index manuel **strictement supérieur**. Les deux valent `null` s'il n'y en a pas.
  Si l'étape courante est manuelle, c'est `step`.
- `awaiting` : `"user"` (action humaine attendue), `"machine"` (réglage prêt à lancer ou en cours), `null`.
- `rev` augmente à chaque changement de la session ; `ingredients` reprend la forme de `RecipeCodec`
  (quantités éventuellement adaptées par l'utilisateur).

### `GET /recipes`
Paramètres : `q` (recherche, même normalisation que l'appli), `category`, `favorite=1`, `limit`, `offset`.
Renvoie des résumés et `rev` (change quand le catalogue change, utilisable en `ETag`).
```json
{"rev": 7, "total": 312, "recipes": [
  {"id": "veloute-de-potiron", "title": "Velouté de potiron", "category": "Soupe", "tags": ["automne"],
   "servings": 4, "prepMinutes": 15, "totalMinutes": 40, "difficulty": 1,
   "rating": 4.5, "ratingCount": 120, "favorite": false, "image": "", "source": "imported"}
]}
```

### `GET /recipes/{id}`
Format `RecipeCodec.toMap` (ingrédients groupés, étapes avec `machine` et `weigh`, `notes`, `author`),
plus `machineSteps`, `ingredientCount` et `imageUrl` (voir §8). `404` si inconnue.
`difficulty` : 1 facile, 2 moyen, 3 difficile. « Type » (boisson, dessert…) = `category` + `tags`.

### `GET /events` (SSE)
Flux `text/event-stream`, un commentaire `:` de maintien toutes les 15 s, `id:` croissant, reprise par `Last-Event-ID`
(rejeu des 50 derniers événements au plus). À la connexion, le flux envoie d'abord l'état courant (`state` puis
`session`, sans `id:`), pour que le client n'ait pas à interroger l'API en plus.

| `event:` | Quand | `data:` |
|---|---|---|
| `state` | changement de `status`, de job, de défaut ; sinon cadence réduite (voir §4) | même forme que `/state` |
| `session` | début, fin, changement d'étape ou de `awaiting` | même forme que `/session` |
| `step` | passage à une nouvelle étape | `{"sessionId","index","kind","text"}` |
| `done` | fin de recette ou abandon | `{"sessionId","recipeId","result":"finished|aborted"}` |
| `fault` | défaut apparu ou levé | `{"code","message","active"}` |
| `recipes` | catalogue modifié (synchro, import) | `{"rev":8,"total":315}` |

## 4. Cadence (protéger la base de HA)

Le `Snapshot` interne sort à ~10 Hz ; l'API n'en expose pas le détail.
- Température : seulement si l'écart ≥ 1 °C ; tr/min : écart ≥ 50 ; poids : écart ≥ 2 g. Au plus un `state` par
  seconde pour ces variations.
- Signal de vie : un `state` au moins toutes les 5 s pendant un job (le temps restant avance), toutes les 30 s sinon.
- Les changements de `status`, couvercle, bol, moteur, chauffe, défaut, job, phase, session et écran partent
  immédiatement.

## 5. Modèle interne à créer dans `core`

`CookSession` / `CookSessions` (pur Kotlin : recette, `stepIndex`, étapes faites, `awaiting`, `startedAt`, `rev`),
pilotées par `CookScreen` et enregistrées dans `prefs.cookSession` par `ToqueApp` : **fait** (commit `cecc9a2`).
Reste à tester dans `core` : le routage, les formats JSON, les seuils de cadence et le serveur (sockets locales).

## 6. Entités Home Assistant (dépôt `toque-homeassistant`)

Un appareil par robot (`id`) ; `sw_version` = version de Toque, `model` = SKMC 1200, `hw_version` = firmware carte.
Config flow : découverte `zeroconf` (`_toque._tcp`) puis jeton. Mise à jour : SSE, avec interrogation de `/state`
toutes les 60 s en repli ; indisponible si plus de réponse.

| Entité | Source |
|---|---|
| `sensor` État (enum : `standby`, `running`, `recipe`, `fault`) | `status` |
| `sensor` Température, Consigne, Vitesse (tr/min), Poids, Temps restant | `machine`, `job` |
| `binary_sensor` Couvercle ouvert, Bol présent, Moteur, Chauffe, Défaut | `machine`, `fault` |
| `sensor` Recette en cours (titre), Étape (`n/N`) | `session` |
| `sensor` Étape en cours, Dernière étape manuelle, Prochaine étape manuelle | `session` ; **état = 255 caractères au plus, texte complet en attribut** |
| attribut `ingredients` de la recette en cours | `session.ingredients` (à exclure du recorder) |
| `event` Cuisine (`step`, `action_requise`, `done`, `fault`) | `/events` |
| `sensor` Nombre de recettes (diagnostic) | `recipeCount` |
| Services avec réponse `toque.list_recipes`, `toque.get_recipe` | `/recipes`, `/recipes/{id}` |
| Action `todo` : ajouter les ingrédients d'une recette à une liste de courses | `/recipes/{id}` |

Diagnostics (firmware, version d'Android, uptime, adresse IP) : catégorie `diagnostic`, inactifs par défaut.

## 7. Décisions

- **`screenOn`** : champ de `/state` (valeur de `PowerManager.isInteractive` lue depuis le service). « Veille » côté
  utilisateur = `status: standby` ; HA combine avec `screenOn` pour l'affichage.
- **Images** : servies par le robot, `GET /recipes/{id}/image`. Voir §8.
- **Nom éditable** : Réglages › Intégrations › Home Assistant › Nom du robot (défaut : « Monsieur Cuisine »).
  Il apparaît dans `/info.name`, dans le TXT mDNS `name` et comme nom d'appareil HA. L'`id` reste stable et
  sert d'identifiant unique HA : renommer le robot ne crée pas un second appareil.
- **Identifiant de session conservé** : `CookSession.id` est enregistré avec la session (là où `guidedRecipe` /
  `guidedStep` le sont aujourd'hui). Après un redémarrage de l'appli ou du robot, la session reprise garde son `id`
  et sa `rev` continue de croître. Un nouvel `id` n'apparaît que pour une nouvelle recette lancée.

## 8. Images

- `recipe.image` (URL d'origine) reste dans le JSON ; en plus, chaque résumé et détail porte
  `"imageUrl": "/api/v1/recipes/{id}/image"` quand le fichier est déjà en cache, sinon `null`.
- Le point d'accès renvoie **le fichier en cache tel quel** (`OnlineSync` l'a déjà redimensionné et recompressé) :
  pas de décodage ni de redimensionnement côté API, donc un coût processeur proche de zéro (une lecture de fichier).
- `ETag` (taille + date du fichier) et `Cache-Control: max-age=86400` : HA ne retélécharge pas une image inchangée.
- Garde-fous : un seul envoi d'image à la fois, lecture par blocs de 16 Kio, `404` si non cachée (jamais de
  téléchargement déclenché par l'API), et pas d'image pendant un job actif (`503` avec `Retry-After: 5`) pour ne pas
  gêner la boucle de communication avec la carte. L'intégration HA demande ses images au démarrage et à l'événement
  `recipes`, pas en continu.
- À mesurer pendant l'implémentation : durée d'un envoi sur le MT6580 pendant un job. Si la boucle machine en souffre,
  la règle « pas d'image pendant un job » reste la protection.

## 9. Points encore ouverts

- Une recette lancée depuis HA n'existe pas (lecture seule) : rien à décider.
- Rétention des images en cache : aujourd'hui sans limite connue, à vérifier avant d'annoncer `imageUrl` pour tout le catalogue.
