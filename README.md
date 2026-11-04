# OCI Grab

Appli Android qui demande en boucle une machine ARM **Always Free** (VM.Standard.A1.Flex) sur Oracle Cloud, jusqu'à ce qu'Oracle en ait une de libre. Dès que c'est le cas, elle crée l'instance et affiche l'IP et la commande SSH.

## Ce que fait l'appli

L'appli est un assistant en 6 étapes avec une barre de progression. **On ne passe à l'étape suivante que si la précédente est valide** (champs corrects, connexion réussie, etc.) ; un message en bas indique ce qui reste à faire.

1. **Système** : choix du système d'exploitation dès le début (Ubuntu, Oracle Linux…).
2. **Compte** : génère une clé API (ou importe un `.pem`), colle la config Oracle depuis le presse-papiers, puis « Se connecter » vérifie le compte (obligatoire pour continuer).
3. **Machine** : domaine de disponibilité, sous-réseau, version du système, taille (1 à 4 cœurs, 6 Go par cœur), disque, nom et délai entre deux tentatives.
4. **SSH** : génère ou importe une paire de clés ; il faut confirmer avoir sauvegardé la clé privée générée.
5. **Protection** : dérogation batterie (obligatoire) et administrateur appareil (conseillé).
6. **Recherche** : récapitulatif, démarrage/arrêt, IP, commande SSH et journal. Service au premier plan avec notification.

**Mes machines** (bouton « Machines » en haut, une fois connecté) : liste des instances du compartiment avec leur état et leur IP, démarrer / arrêter / redémarrer, renommer, changer la taille (cœurs et mémoire, avec redémarrage) et supprimer (confirmation en tapant le nom ; le disque de démarrage est supprimé avec la machine).

## Les fichiers

| Fichier | Rôle |
|---|---|
| `app/src/main/java/com/aurelien/ocigrab/MainActivity.kt` | Interface : champs, listes, boutons, journal |
| `.../GrabService.kt` | Service en arrière-plan : boucle de tentatives, notification, récupération de l'IP |
| `.../OciClient.kt` | Appels à l'API Oracle (connexion signée, création d'instance, listes) |
| `.../KeyUtil.kt` | Génération et conversion des clés (API et SSH) |
| `.../Store.kt` | Sauvegarde de la config, journal et état de la recherche |
| `.../AdminReceiver.kt` | Récepteur de l'administrateur appareil |
| `app/src/main/AndroidManifest.xml` | Permissions, activité, service, récepteur |
| `app/src/main/res/xml/device_admin.xml` | Règles de l'administrateur appareil |
| `app/build.gradle`, `build.gradle`, `settings.gradle` | Configuration Gradle (AGP 8.3.2, Kotlin 1.9.24, SDK 34, min 26) |
| `gradlew`, `gradlew.bat`, `gradle/wrapper/` | Wrapper Gradle 8.4 |
| `.github/workflows/build.yml` | Compile l'APK automatiquement sur GitHub Actions |

## Compiler

Chaque push lance GitHub Actions. L'APK est dans **Actions › dernier run › Artifacts › OciGrab-debug-apk**.
En local : `./gradlew assembleDebug` (JDK 17), APK dans `app/build/outputs/apk/debug/`.

## Sécurité

La clé privée API est stockée dans l'appli, sur le téléphone. Ne la mets jamais dans le dépôt, et utilise une clé API dédiée à cette appli.
