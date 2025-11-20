package LiveSpeechRecognition;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.LibVosk;

import javax.sound.sampled.*;

public class LiveSpeechRecognition {

    public static void main(String[] args) {
        try {

            // Charger ton modèle depuis resources
            Model model = new Model("src/main/resources/model/vosk-model");

            // Config micro (16 kHz recommandé)
            AudioFormat format = new AudioFormat(16000, 16, 1, true, false);
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);

            if (!AudioSystem.isLineSupported(info)) {
                System.out.println("Microphone non supporté.");
                return;
            }

            TargetDataLine microphone = (TargetDataLine) AudioSystem.getLine(info);
            microphone.open(format);
            microphone.start();

            Recognizer recognizer = new Recognizer(model, 16000);

            byte[] buffer = new byte[4096];

            System.out.println("Parle maintenant…");

            while (true) {
                int bytesRead = microphone.read(buffer, 0, buffer.length);

                if (bytesRead > 0) {

                    // Phrase terminée → résultat final
                    if (recognizer.acceptWaveForm(buffer, bytesRead)) {
                        String result = recognizer.getResult().replace("\n", "");

                        // Effacer la ligne de statut
                        System.out.print("\r\033[K");

                        // Afficher proprement le résultat détecté
                        System.out.println("🟢 Phrase détectée : " + result);
                    }

                    // Phrase en cours → mise à jour sur la même ligne
                    else {
                        String partial = recognizer.getPartialResult().replace("\n", "");

                        // Réécrit la ligne continue
                        System.out.print("\rÉcoute… " + partial);
                        System.out.flush();
                    }
                }
            }


        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

