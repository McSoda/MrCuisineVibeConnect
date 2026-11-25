# Tests de la version 0.10.0.1

Compte rendu des essais faits le 25 novembre 2026. C'est la 0.10.0 de Wuki60 (dont Toque Compagnon, l'application pour
téléphone) avec nos ajouts (voir les [notes de la release](https://github.com/McSoda/MrCuisineVibeConnect/releases/tag/v0.10.0.1)).

**Robot** : le même que pour la 0.9.0.1, ci-dessous. **APK essayé sur le robot** : construit sur le PC à partir des
sources publiées (versionCode 23) et installé par adb. L'APK de la release n'a pas été installé ; son numéro de version
et son certificat de signature ont été vérifiés (le même que pour les versions précédentes).

## Tests automatiques (0.10.0.1)

- Tests unitaires de `core` et de `app` (dont ceux du compagnon) : 641 tests, aucun échec.
- `gradlew :app:assembleRelease` : réussi.
- `tools\setup\test-setup.ps1` : 37 réussis, 0 échec.

## Essais sur le robot (0.10.0.1)

| Essai | Ce qui a été fait | Résultat |
|---|---|---|
| Démarrage | Installation par-dessus la 0.9.0.1 | Base « prête » environ 4 s après le démarrage de l'appli, catalogue de 1662 recettes prêt en 10,7 s, `/info` annonce 0.10.0.1. |
| Réglages | *Avancé* et la nouvelle page *Téléphone* | Le bloc Home Assistant est toujours dans *Avancé* (API à l'écoute sur le port 8765). *Lancement depuis le téléphone* est **activé par défaut**. |
| Écran d'appairage | *Téléphone › Appairer un téléphone*, puis retour sans appairer | QR code et code affichés, serveur du compagnon ouvert sur le port 8088, puis fermé en quittant l'écran. |
| Reprise d'une recette guidée | Étape 3, retour à l'accueil, arrêt forcé de l'appli, relance, « Reprendre » | Même session (même identifiant dans l'API), même étape. |
| Abandon d'une recette | Croix de la carte « Reprendre », puis confirmation | `/session` renvoie `{"active": false}`, la carte disparaît. |

Aucun programme machine n'a été lancé pendant ces essais.

## Non testé (0.10.0.1)

- L'appairage réel d'un téléphone, et tout ce qui se fait depuis le téléphone (dont le lancement d'une cuisson).
- Une cuisson, une fin de recette, les minuteurs et la veille (essayés sur la 0.9.0.1, ci-dessous).
- La mise à jour depuis l'appli vers la 0.10.0.1.
- La synchro nocturne avec la 0.10.0.1.
- Le blocage provoqué de la boucle machine (chien de garde).
- L'installation depuis un Mac avec le script corrigé pour télécharger notre APK.

---

# Tests de la version 0.9.0.1

Compte rendu des essais faits sur un robot réel avant et après la publication de la 0.9.0.1 (24 et 25 novembre 2026).
C'est la 0.9.0 de Wuki60 avec, en plus, l'API Home Assistant, la session de cuisson (`CookSession`) et la
source de mises à jour de ce dépôt (voir les [notes de la release](https://github.com/McSoda/MrCuisineVibeConnect/releases/tag/v0.9.0.1)).

**Robot** : Monsieur Cuisine Connect SKMC 1200, firmware de la base 4922, tablette Android 6.0, adresse fixe sur le
réseau local. **APK** : `MCC-285-Toque-0.9.0.1.apk` (versionCode 22), signé avec la clé du projet.

## Tests automatiques

- `gradlew :core:test` : 409 tests, aucun échec sur la version publiée.
- Un test d'une version précédente, `SmartControllerTest.loopShutdownSendsStopFrames`, a échoué une fois avec une
  `ConcurrentModificationException` (liste du test lue pendant que la boucle y écrit), puis est repassé trois fois sur
  trois. Il n'est pas lié aux changements de cette version.
- `gradlew :app:assembleRelease` : l'APK du workflow GitHub a le même certificat de signature que les builds locaux.

## Essais sur le robot

| Essai | Ce qui a été fait | Résultat |
|---|---|---|
| Mise à jour depuis l'appli | 0.8.2.1 installée par adb, puis *Réglages › Mises à jour* | Version publiée vue (0.9.0.1), APK téléchargé, installé en root, appli relancée en 0.9.0.1, base « prête » en 5 s. Aucune erreur de signature. |
| Pages de réglages | *Écran* et *Avancé* parcourues | Menu de familles à gauche. Le bloc Home Assistant (interrupteur, nom, port, adresse, jeton, « Nouveau jeton ») est complet dans *Avancé*, au-dessus des outils. |
| Reprise d'une recette guidée | Recette de 24 étapes jusqu'à l'étape 3, retour à l'accueil, arrêt forcé de l'appli, relance | Même session (même identifiant dans l'API), même étape, carte « Reprendre » sur l'accueil. |
| Arrêt d'une recette | « Arrêter la recette » puis confirmation | `/session` renvoie `{"active": false}`, la carte disparaît. |
| Fin d'une recette | Toutes les étapes jusqu'à « Terminer », sans lancer aucun programme | « Bon appétit ! », session terminée côté API, retour à la fiche recette. |
| Minuteur d'étape | Étape à la main « Laisser reposer 15 min » | Minuteur proposé avec la durée lue dans le texte, pastille en haut de l'écran, panneau des minuteurs, « +1 min », annulation. |
| Minuteur et redémarrage | Arrêt forcé de l'appli avec un minuteur en cours | Le minuteur continue, la recette reprend à la bonne étape. |
| Fin de minuteur | Minuteur de 1 min | Fenêtre « Minuteur terminé », sonnerie entendue. |
| Fin de minuteur pendant la veille | Minuteur de 7 min (veille à 5 min), puis minuteur de 4 min 30 (veille à 2 min), écran non touché | L'écran passe en veille (`screenOn: false` dans l'API) puis se rallume à la fin du minuteur. Le premier essai n'a pas été entendu (personne n'écoutait) ; au second, la sonnerie a été entendue. La lecture audio est visible dans le journal système (une toutes les 3 s pendant 2 min). |

## Chien de garde (PR #65)

Essais faits avec la correction de badrbelhaj1 (le chien de garde n'écrit plus son STOP par `emergencyStop()`, dont le
verrou empêchait de repiloter), sur la version précédant la reprise sur la 0.9.0 (0.8.2 + ce correctif), avec un crochet
d'essai qui bloque la boucle pendant une durée donnée (hors de toute version publiée). Robot à vide, bol vide.

| Blocage | Résultat |
|---|---|
| 3 s | Détecté après 2,1 s, défaut « arrêt d'urgence », liaison fermée, boucle repartie seule, liaison rouverte et « Prête » environ 14 s après la détection. Alerte acquittée, cuisson manuelle lancée : **le moteur tourne**. |
| 8 s | Détecté après 2,1 s, processus machine abandonné à 6,1 s puis relancé par Android, liaison rouverte et « Prête » environ 16 s après la détection. |

Le code du chien de garde est le même dans la 0.9.0.1 (il vient de la 0.9.0). Les essais de blocage n'ont pas été
rejoués sur la 0.9.0.1 elle-même.

## Synchro nocturne des recettes

Vérifiée le 25 novembre 2026 dans le journal du robot (synchro automatique prévue vers 3 h 30).

| Nuit | Version | Journal |
|---|---|---|
| 22 (19 h 50), 23 et 24 novembre | précédentes | « Synchronisation impossible » : `NoSuchMethodError`, `HashMap.putIfAbsent` n'existe pas sur Android 6 (API 23). |
| 25 novembre, 3 h 39 | 0.9.0.1 | **« 1662 recettes Lidl à jour »**, sans erreur. |

Le correctif (PR #78 de badrbelhaj1, `getOrPut` à la place de `putIfAbsent`) vient de la 0.9.0. La ligne corrigée
ne s'exécute que si une recette retirée est en favori, ajustée ou déjà cuisinée : les trois plantages montrent que
c'était le cas sur ce robot. Le report des favoris et des ajustements n'a pas été vérifié recette par recette.

Home Assistant n'a vu aucune coupure du robot pendant la nuit.

## Non testé

- Un blocage de la boucle pendant une vraie cuisson (moteur et chauffe en marche).
- Une cuisson réelle complète avec la 0.9.0.1 (la recette complète « Soupe de chou, pommes de terre et carottes » a été
  cuisinée sur une version plus ancienne : 0.7.1 avec les trois PR).
- Un minuteur lancé depuis une étape avec un programme machine, et la fin d'un programme pendant la veille.
- La mise à jour in-app sur un autre robot, un SKMC 1100 A1 ou un Monsieur Cuisine Smart (le Smart n'a pas d'APK dans ce
  dépôt : il vient du dépôt d'origine).
- Les pages de réglages autres que *Écran* et *Avancé*.
