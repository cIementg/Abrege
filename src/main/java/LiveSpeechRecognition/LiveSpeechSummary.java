package LiveSpeechRecognition;

import org.vosk.Model;
import org.vosk.Recognizer;

import javax.sound.sampled.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class LiveSpeechSummary {

    // ---------- CONTEXTE GLOBAL DES RÉSUMÉS ----------
    private static final StringBuilder history = new StringBuilder();
    // Limite du contexte envoyé au modèle (pour éviter prompts trop longs)
    private static final int MAX_CONTEXT_CHARS = 2000;

    public static void main(String[] args) {

        try {
            Model model = new Model("src/main/resources/model/vosk-model");

            AudioFormat format = new AudioFormat(16000, 16, 1, true, false);
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);

            if (!AudioSystem.isLineSupported(info)) {
                System.out.println("Micro non supporté !");
                return;
            }

            TargetDataLine microphone = (TargetDataLine) AudioSystem.getLine(info);
            microphone.open(format);
            microphone.start();

            Recognizer recognizer = new Recognizer(model, 16000);
            byte[] buffer = new byte[8192];

            System.out.println("\n🎤 Parle maintenant…\n");

            while (true) {

                int bytesRead = microphone.read(buffer, 0, buffer.length);

                if (bytesRead > 0) {

                    if (recognizer.acceptWaveForm(buffer, bytesRead)) {

                        String text = extractText(recognizer.getResult());

                        if (!text.isEmpty()) {
                            System.out.println("\n🟢 Phrase détectée : " + text);

                            System.out.print("🟣 Résumé : ");
                            streamSummary(text); // Streaming live
                            System.out.println("\n");
                        }

                    } else {
                        String partial = extractText(recognizer.getPartialResult());
                        if (!partial.isEmpty()) {
                            System.out.print("\r⏳ Écoute… " + partial + "     ");
                        }
                    }
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Nettoyage JSON Vosk → extrait juste le texte
    private static String extractText(String json) {
        if (json == null || json.isEmpty()) return "";

        int idx = json.indexOf("\"text\"");
        if (idx == -1) return "";

        String cleaned = json.substring(idx + 7).replaceAll("[\":{}]", "").trim();
        return cleaned;
    }

    // Récupère le contexte courant tronqué et échappé pour JSON
    private static String getContextForPrompt() {
        synchronized (history) {
            if (history.length() == 0) return "";
            String ctx = history.toString().trim();
            if (ctx.length() > MAX_CONTEXT_CHARS) {
                // garder la fin (les résumés les plus récents)
                ctx = ctx.substring(ctx.length() - MAX_CONTEXT_CHARS);
            }
            // échapper les guillemets et backslashes pour injecter en JSON
            ctx = ctx.replace("\\", "\\\\").replace("\"", "\\\"");
            return ctx;
        }
    }

    // Ajoute un résumé au contexte (thread-safe)
    private static void appendToHistory(String summary) {
        if (summary == null || summary.isBlank()) return;
        synchronized (history) {
            if (history.length() > 0) history.append(" ");
            history.append(summary.trim());
            // Optionnel : limiter la taille physique
            if (history.length() > MAX_CONTEXT_CHARS * 2) {
                // tronquer le début si l'historique grandit trop (garder la fin)
                history.delete(0, history.length() - MAX_CONTEXT_CHARS);
            }
        }
    }

    // ---- STREAMING OLLAMA ---- //
    public static void streamSummary(String text) {

        try {
            URL url = new URL("http://localhost:11434/api/generate");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();

            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");

            // Échapper le texte courant
            String safeText = text.replace("\\", "\\\\").replace("\"", "\\\"");

            // Construire le prompt en incluant le contexte (tronqué)
            String contextEscaped = getContextForPrompt();

            StringBuilder prompt = new StringBuilder();
            prompt.append("Tu dois résumer uniquement la phrase actuelle.\n")
                    .append("Le contexte sert seulement à comprendre, pas à résumer.\n")
                    .append("Règles :\n")
                    .append("- Résumé très court, 1 phrase.\n")
                    .append("- Pas d'ajout.\n")
                    .append("- Pas d'explication.\n")
                    .append("- Pas de définition.\n")
                    .append("- Pas de politesse.\n")
                    .append("- Pas de reformulation longue.\n")
                    .append("- Si inutile : 'Rien à résumer'.\n\n");

            if (!contextEscaped.isEmpty()) {
                prompt.append("Contexte précédent (résumés) : ").append(contextEscaped).append("\n\n");
            }

            prompt.append("Phrase actuelle : ").append(safeText);

            // JSON body (stream true pour token streaming)
            String body = "{"
                    + "\"model\": \"qwen2.5:1.5b\","
                    + "\"prompt\": \"" + prompt.toString().replace("\n", "\\n").replace("\r", "") + "\","
                    + "\"stream\": true"
                    + "}";

            byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bodyBytes.length);

            try (OutputStream os = connection.getOutputStream()) {
                os.write(bodyBytes);
            }

            // lire le stream d'Ollama token par token
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {

                String line;
                StringBuilder summaryCollector = new StringBuilder();

                while ((line = reader.readLine()) != null) {

                    if (!line.contains("\"response\""))
                        continue;

                    String token = line.replaceAll(".*\"response\":\"", "")
                            .replaceAll("\".*", "");

                    // Affiche token par token
                    System.out.print(token);
                    System.out.flush();

                    // Accumule pour l'historique
                    summaryCollector.append(token);
                }

                // Après la fin du stream, on ajoute le résumé au contexte
                String finalSummary = summaryCollector.toString().trim();
                if (!finalSummary.isEmpty() && !finalSummary.equalsIgnoreCase("Rien à résumer")) {
                    appendToHistory(finalSummary);
                }
            }

        } catch (Exception e) {
            System.out.println("❌ Erreur Ollama : " + e.getMessage());
        }
    }
}
