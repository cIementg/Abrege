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
                    if (recognizer.acceptWaveForm(buffer, bytesRead)) {
                        System.out.println("Résultat : " + recognizer.getResult());
                    } else {
                        System.out.println("Partiel : " + recognizer.getPartialResult());
                    }
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

