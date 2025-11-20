## Abregé – Reconnaissance vocale + résumé local

Ce projet Spring Boot propose une reconnaissance vocale française en temps réel couplée à une première couche de résumé/localisation d’information. Toute l’inférence reste en local grâce à Vosk pour la transcription et à Ollama pour les modèles de langage.

### 1. Prérequis
- JDK 17+ (Gradle wrapper déjà fourni).
- [Vosk](https://alphacephei.com/vosk/) avec le modèle `vosk-model-fr-0.6-linto-2.2.0`.
- [Ollama](https://ollama.com/) pour exécuter un LLM local (ex. `llama3`, `mistral`, etc.).
- Carte son/micro et permissions nécessaires.

### 2. Installation du modèle Vosk
1. Télécharger `vosk-model-fr-0.6-linto-2.2.0` depuis <https://alphacephei.com/vosk/models>.
2. Extraire l’archive dans `src/main/resources/model/`.
3. Renommer le dossier extrait en `model` (le chemin final doit être `src/main/resources/model/vosk-model/...` comme attendu par l’application).
4. Vérifier que les sous-dossiers `am`, `conf`, `graph`, `ivector`, etc. sont bien présents.

> Astuce : éviter les chemins contenant des espaces/accents lorsque vous lancez l’appli sous Windows.

### 3. Mise en place d’Ollama
1. Installer Ollama selon votre OS : <https://ollama.com/download>.
2. Lancer le service `ollama serve`.
3. Télécharger un modèle local : par exemple `ollama pull llama3`.
4. Adapter la configuration de l’application pour pointer vers l’endpoint Ollama (par défaut `http://localhost:11434`).

### 4. Configuration de l’application Spring
- Le fichier `src/main/resources/application.properties` comporte les paramètres audio et les URLs (Vosk/Ollama). Ajustez-les si besoin.
- Si vous changez l’emplacement du modèle, exposez le chemin via une propriété (`vosk.model-path`) et mettez-le à jour dans la configuration Java correspondante.

### 5. Lancement
```bash
./gradlew bootRun
```
Puis parler dans votre micro : le flux texte apparaît dans la console et le résumé généré par Ollama suit.

### 6. Tests rapides
- Vérifier la reconnaissance brute : désactiver temporairement l’appel Ollama pour valider que Vosk fonctionne (classe `LiveSpeechRecognition`).
- Vérifier la chaîne complète : relancer Ollama, observer les logs de `LiveSpeechSummary`.

### 7. Dépannage
- **Pas de son détecté** : s’assurer que la source micro par défaut est accessible à la JVM.
- **Erreur modèle Vosk** : vérifier le renommage en `model` et les droits de lecture.
- **Timeout Ollama** : relancer `ollama serve`, vérifier la RAM disponible et que votre modèle est bien téléchargé.

### 8. Aller plus loin
- Ajouter d’autres voix/langues en téléchargeant des modèles Vosk supplémentaires.
- Expérimenter différents LLM via `ollama pull`.
- Intégrer une interface web (WebSocket) pour diffuser transcription et résumé en live.

Bon build !

