# Pulse Fit Engine

Application Android Kotlin pour afficher un tableau de bord temps reel base sur la table `Metric.type` extraite de Garmin Connect.

## Etat actuel

- application Android native en Kotlin
- ecran live avec cartes de metriques
- table `Metric.type -> signification` integree dans le code
- formattage metier pour FC, allure, vitesse, puissance, distance, respiration, calories
- source temps reel simulee qui met a jour l'UI chaque seconde
- point d'extension `GarminRealtimeDecoder` pour brancher le vrai flux protobuf Garmin

## Ce qu'il reste a brancher

- le transport exact montre <-> telephone
- le point d'entree Android qui recoit les trames
- le parseur reel `AlertNotification` / `LiveSessionEventNotification`

## Build local

```powershell
$env:JAVA_HOME = "C:\path\to\jdk-21"
.\gradlew.bat assembleDebug
```

## Structure

- `app/src/main/java/com/astor/pulsefitengine/data` : modeles Garmin et source live
- `app/src/main/java/com/astor/pulsefitengine/ui` : etat d'ecran et adapter RecyclerView
- `app/src/main/java/com/astor/pulsefitengine/MainActivity.kt` : ecran principal
