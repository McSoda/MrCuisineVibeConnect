# MrCuisineVibeConnect (fork avec une API Home Assistant)

🇬🇧 **[English version](README.md)**

Ceci est un fork de **[Wuki60/MrCuisineVibeConnect](https://github.com/Wuki60/MrCuisineVibeConnect)**, le dépôt de
**Toque**, une interface de remplacement pour les robots de cuisine **Monsieur Cuisine** de Lidl.

**Tout ce que nous avons en commun est documenté là-bas :** ce que fait Toque, les robots pris en charge, les guides
d'installation, le site communautaire, les problèmes connus et les crédits. Commencez par
[son README](https://github.com/Wuki60/MrCuisineVibeConnect/blob/main/README.fr.md).

Cette page ne décrit que les différences.

## Intégration Home Assistant

**[McSoda/toque-homeassistant](https://github.com/McSoda/toque-homeassistant)** lit l'API de ce fork et fait apparaître
le robot dans Home Assistant. À ajouter dans HACS comme dépôt personnalisé (catégorie *Intégration*). Elle ne
fonctionne pas avec le Toque de Wuki60, qui n'a pas cette API.

## Ce qui change

Basé sur Toque **0.10.0**. Nos versions ajoutent un quatrième nombre (**0.10.0.1** est la première basée sur la 0.10.0).
Les versions plus récentes de Wuki60 ne sont pas incluses tant qu'elles n'ont pas été fusionnées ici.

| | Ce fork |
|---|---|
| **API Home Assistant** | Une API HTTP/SSE locale, **en lecture seule** : état du robot, recette guidée en cours, recettes et diagnostics. **Désactivée par défaut** ; à activer dans *Réglages › Avancé › Home Assistant* (port 8765, jeton d'accès). Rien ne peut être commandé à distance. [Spécification](docs/HOME_ASSISTANT_API.md) (en anglais) |
| **Session de cuisson** | L'état de la recette guidée vit dans `core` (`CookSession`) : il survit à un redémarrage de l'appli et il est publié par l'API. |
| **Mises à jour** | *Réglages › Mises à jour* lit **les releases de ce dépôt**. |
| **APK** | `MCC-285-Toque-x.y.z.apk`, pour le **Monsieur Cuisine Connect uniquement**. Nous ne publions pas d'APK Smart : les propriétaires de Smart doivent utiliser [le dépôt d'origine](https://github.com/Wuki60/MrCuisineVibeConnect/releases/latest). |
| **Tests** | Un contrôle du codec Smart contre les vecteurs « manual » du SDK, et le [compte rendu des essais de la 0.9.0.1 sur un robot réel](docs/TESTS-0.9.0.1.md). Nous n'avons testé que sur un SKMC 1200. |

## Installer ce fork

Suivez les [guides d'installation de Wuki60](https://github.com/Wuki60/MrCuisineVibeConnect/blob/main/README.fr.md),
depuis **ce** dépôt, avec ces différences :

- **Windows et macOS** : les scripts d'installation de ce dépôt (`tools\setup\toque-setup.ps1`,
  `tools/setup/toque-setup.sh`) téléchargent notre APK depuis notre
  [dernière release](https://github.com/McSoda/MrCuisineVibeConnect/releases/latest). Si le dossier de travail contient
  déjà un APK de Wuki60 avec un numéro de version plus élevé, l'étape d'installation le choisit : supprimez-le ou
  indiquez notre fichier.
- **Robot sur lequel Toque tourne déjà** : depuis le dossier du dépôt,
  `powershell -ExecutionPolicy Bypass -File tools\install-robot.ps1 -Apk <dossier>\MCC-285-Toque-x.y.z.apk`.
- [AGENTS.md](AGENTS.md) est le fichier de Wuki60 : là où il nomme `Toque-x.y.z.apk` ou ses releases, prenez les nôtres.

## Questions et crédits

Toque est l'œuvre de Wuki60 et de ses contributeurs ([licence MIT](LICENSE)). Projet indépendant, non affilié à Lidl,
SilverCrest ou Hoyer ; à utiliser à vos risques. Pour tout ce qui n'est pas propre à ce fork, utilisez les
[issues du dépôt d'origine](https://github.com/Wuki60/MrCuisineVibeConnect/issues).
