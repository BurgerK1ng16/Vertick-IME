import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Base64;

/** Signs the exact UTF-8 catalog payload consumed by DictionaryPackManager. */
public final class DictionaryManifestSigner {
  private DictionaryManifestSigner() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 6) {
      throw new IllegalArgumentException(
          "usage: <keystore.p12> <store-password> <alias> <key-password> <payload.json> <manifest.json>");
    }
    KeyStore keyStore = KeyStore.getInstance("PKCS12");
    try (var input = Files.newInputStream(Path.of(args[0]))) {
      keyStore.load(input, args[1].toCharArray());
    }
    PrivateKey key = (PrivateKey) keyStore.getKey(args[2], args[3].toCharArray());
    if (key == null || !"EdDSA".equalsIgnoreCase(key.getAlgorithm())) {
      throw new IllegalArgumentException("Keystore entry must be an Ed25519 private key");
    }
    byte[] payload = Files.readAllBytes(Path.of(args[4]));
    Signature signature = Signature.getInstance("Ed25519");
    signature.initSign(key);
    signature.update(payload);
    String envelope = "{\"payload\":\"" + Base64.getEncoder().encodeToString(payload)
        + "\",\"signature\":\"" + Base64.getEncoder().encodeToString(signature.sign()) + "\"}";
    Files.writeString(Path.of(args[5]), envelope, StandardCharsets.UTF_8);
  }
}
