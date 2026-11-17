# Analyse du code de Toque

Date : 2026-11-17. Lecture de `Safety.kt`, `Updater.kt`, `RootSerial.kt`, `Releases.kt`,
début de `MachineController.kt` et `app/build.gradle.kts`. L'UI, les scripts PowerShell et les
tests n'ont pas été exécutés ni relus en détail.

## Architecture

- `core/` : Kotlin pur, sans Android, testé. Protocole série (`protocol/Mcc.kt`), règles de
  sécurité (`machine/Safety.kt`), contrôleur (`MachineController.kt`) et boucle (`MachineLoop.kt`),
  recettes, JSON maison, i18n, comparaison de versions.
- `app/` : appli Android (minSdk 23, sans AndroidX, vues construites en code). UI, `MachineService`,
  synchronisation des recettes, `Updater`.
- `tools/` : scripts PowerShell et shell d'installation/restauration, `twin.py` (jumeau simulé de
  la base moteur, utilisé par `TwinIntegrationTest`).

## Points forts

- `SafetyPolicy` est une fonction pure qui renvoie des violations codées : vitesse en chauffe et en
  sens inverse, durées maximales, limite du fouet, Turbo bloqué à chaud, confirmation du mixage chaud.
- Le contrôleur corrige les défauts de l'appli d'origine : préconditions avant chaque démarrage,
  échantillon frais, armement sur 3 échantillons, STOP confirmé par une trame reçue après lui, arrêt
  si la liaison devient muette, alertes verrouillées jusqu'à acquittement, horloge monotone.
- Code découplé (`Clock`, `Transport`) et testable sans matériel ; golden files de trames.

## Points à surveiller

1. **Clé de signature publique** (`app/signature/toque.p12`, mot de passe `toque-public` dans
   `app/build.gradle.kts`). Choix assumé pour que les mises à jour s'installent par-dessus, mais
   Android ne protège plus contre un APK malveillant signé avec la même clé. L'`Updater` ne vérifie
   que le nom du paquet et le `versionCode`, ni l'hôte de `apkUrl` (`Releases.parse`), ni un hash,
   puis installe en root avec `pm install -r`. La confiance repose sur HTTPS vers GitHub et sur le
   compte du dépôt.
   Piste : contrôler l'hôte (`github.com`, `objects.githubusercontent.com`) et publier un SHA-256
   dans la release, à vérifier avant installation.
2. **Concurrence dans `RootShell`** (`RootSerial.kt:33`) : `drained` est un `WeakHashMap` non
   synchronisé et partagé par tous les appels de `run`. Des appels simultanés depuis plusieurs fils
   peuvent le corrompre. Piste : renvoyer le buffer directement depuis `drain`.
3. **Détection d'échec fragile dans `installRoot`** (`Updater.kt:134`) : recherche de « Failure » ou
   « Error » dans la sortie. Si `pm install` ne se termine pas et n'écrit rien, l'utilisateur attend
   180 s sans retour.
4. **Détenteurs du port série** (`RootSerial.kt:114`) : la condition `cmd.contains("cat") …` peut
   tuer un processus étranger dont la ligne de commande contient « cat ». Piste : reconnaître
   précisément le pont de l'appli.
5. **Fichiers volumineux** : `MachineController` (677 lignes), `MainActivity` (454), `SettingsScreen`
   (492) mériteraient d'être découpés.
6. **`targetSdk = 28`** avec lints d'obsolescence désactivés : cohérent avec Android 6, à garder en
   tête pour toute distribution hors de ce robot.

## Verdict

La partie critique (sécurité du moteur et de la chauffe) est solide et bien testée. Le principal
risque est la chaîne de mise à jour (point 1), car elle s'exécute avec les droits root. Les points 2
et 4 sont de petites corrections.

Non vérifié : `gradlew :core:test` et la compilation n'ont pas été lancés.
