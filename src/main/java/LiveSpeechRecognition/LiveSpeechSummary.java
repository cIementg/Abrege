package LiveSpeechRecognition;

import org.vosk.Model;
import org.vosk.Recognizer;

import javax.sound.sampled.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;

public class LiveSpeechSummary {

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
            byte[] buffer = new byte[4096];

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

    // ---- STREAMING OLLAMA ---- //
    public static void streamSummary(String text) {

        try {
            URL url = new URL("http://localhost:11434/api/generate");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();

            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");

            text = text.replace("\"", "\\\"");

            String body = "{"
                    + "\"model\": \"gemma3:4b\","
                    + "\"prompt\": \"Résume de façon très courte, et ne donne pas de définition : " + text + "\","
                    + "\"stream\": true"
                    + "}";

            OutputStream os = connection.getOutputStream();
            os.write(body.getBytes());
            os.flush();

            BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));

            String line;
            while ((line = reader.readLine()) != null) {

                if (!line.contains("\"response\""))
                    continue;

                // On extrait UNIQUEMENT la réponse
                String token = line.replaceAll(".*\"response\":\"", "")
                        .replaceAll("\".*", "");

                System.out.print(token);
                System.out.flush();
            }

        } catch (Exception e) {
            System.out.println("❌ Erreur Ollama : " + e.getMessage());
        }
    }
}
