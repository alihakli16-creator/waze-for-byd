# Waze for BYD — Kurulum ve APK Derleme Rehberi

Bu repo, BYD DiLink multimedya ve dijital gösterge paneli (instrument cluster) için özel olarak yamalanmış **Waze for BYD** projesidir.

---

## 1. Hangi Waze Sürümüyle Kullanmanız Gerekiyor?

Projenin en güncel sürümü (**test.10**) için gereken Waze sürümü:

* **Tam Sürüm:** **Waze 5.26.0.0** (versionCode: `1030742`)
* **Mimari:** `arm64-v8a` (Android 10+)
* **Dosya Biçimi:** APKMirror Split Bundle (`.apkm`)
* **Kaynak Dosya Adı:**
  `com.waze_5.26.0.0-1030742_1arch_2dpi_24lang_115dd2209a60866ddb880cd03246f5ac_apkmirror.com.apkm`
* **Kaynak Dosya SHA-256 Doğrulaması:**
  `e44c3eb2a974b689950c89fe038aea1758b234886bf113032e7d211329532ab1`
* **Birleştirilmiş APK (Merged) SHA-256 Doğrulaması:**
  `66a367a5a55b9d808fcb6e613f96adc6148709fa563666810a90b9daf849bb49`

> ⚠️ **Not:** Eski test sürümleri (test.7 ve öncesi) Waze 5.22.90.123 kullanmaktaydı; ancak güncel test.8, test.9 ve **test.10** sürümleri kesin olarak **Waze 5.26.0.0** gerektirir. Kod yamaları doğrudan bu sürümün smali kod bloklarına kilitlidir.

---

## 2. Neden Doğrudan APK Olarak Repoda Bulunmuyor?

1. **Telif Hakları:** Waze'in orijinal tescilli APK dosyaları telif sebebiyle açık kaynak depolarda dağıtılmaz.
2. **GitHub Dosya Boyutu Sınırı:** Birleştirilmiş Waze APK'sı yaklaşık 130 MB'dır. GitHub normal `git push` işlemlerinde 100 MB üzerindeki tekil dosyaları reddeder.

Bu sebeple repo içerisine **GitHub Actions** otomatik derleme altyapısı (`.github/workflows/build-apk.yml`) eklendi.

---

## 3. GitHub'ınızdan APK Olarak Nasıl İndireceksiniz?

Size hazırlanan bu zip dosyasındaki dosyaları GitHub reponuza (`alihakli16-creator/waze-for-byd`) push ettikten sonra aşağıdaki **3 yöntemden birini** kullanarak APK'nızı otomatik derletebilirsiniz:

### 🌟 Yöntem 1: Doğrudan İndirme Linki ile (En Kolayı)
1. APKMirror'dan **Waze 5.26.0.0 (arm64-v8a)** bundle (`.apkm`) dosyasını indirin.
2. Bu dosyayı Google Drive, Dropbox, Catbox.moe veya WeTransfer gibi bir yere yükleyip doğrudan indirme linkini (direct download link) alın.
3. GitHub reponuza gidin: `https://github.com/alihakli16-creator/waze-for-byd`
4. Üstteki **Actions** sekmesine tıklayın.
5. Sol menüden **Build Waze for BYD APK** iş akışını seçin.
6. Sağdaki **Run workflow** butonuna tıklayın:
   - `waze_apk_url`: Aldığınız indirme linkini buraya yapıştırın.
   - `create_release`: `true` olarak bırakın.
7. **Run workflow** diyerek başlatın.
8. Yaklaşık 3-4 dakika sonra işlem tamamlanacak:
   - **Releases** sekmesine girip derlenmiş `Waze-for-BYD-5.26.0.0-test.10.apk` dosyasını tek tıkla indirin!
   - Veya **Actions** sayfasındaki çalıştırma özetinin en altındaki **Artifacts** kısmından zip halinde indirin.

---

### 📦 Yöntem 2: GitHub Releases Üzerinden Yükleyerek
GitHub Releases tek dosyada 2 GB'a kadar izin verir (100 MB sınırına takılmaz):
1. GitHub reponuzda sağ taraftaki **Releases** bölümüne tıklayın -> **Create a new release**.
2. Tag alanına `base` yazın.
3. İndirdiğiniz `.apkm` dosyasını veya APKEditor ile birleştirdiğiniz `waze-5-26-0-0.apk` dosyasını bu release sayfasına sürükleyip bırakın ve kaydedin.
4. **Actions** sekmesine gidip **Build Waze for BYD APK** iş akışını çalıştırın.
5. Sistem otomatik olarak release'deki dosyayı indirecek, yamalayacak ve yeni sürüm APK'sını üretecektir.

---

### 💻 Yöntem 3: APKM Dosyasını Repoya Atıp Push Etmek
APKMirror'dan inen `.apkm` paketi sıkıştırılmış olduğu için genellikle 80-90 MB arasındadır (GitHub'ın 100 MB limitinin altındadır):
1. `.apkm` dosyasını doğrudan proje ana dizinine koyun.
2. `git add .`, `git commit -m "Add base apkm"`, `git push origin main` yapın.
3. Push yaptığınız anda GitHub Actions otomatik olarak devreye girecek ve APK'yı derleyecektir.

---

## 4. BYD Aracına Kurulum ve Kullanım

1. İndirdiğiniz `Waze-for-BYD-5.26.0.0-test.10.apk` dosyasını USB belleğe atıp aracınızın multimedya sistemine yükleyin (veya ADB ile kurun):
   ```sh
   adb install -r Waze-for-BYD-5.26.0.0-test.10.apk
   ```
2. Uygulama adı araçta **Waze for BYD** olarak görünecektir.
3. Direksiyon tuşlarıyla harita yakınlaştırma (zoom) ve gösterge paneli aktarımı varsayılan olarak desteklenmektedir.
4. İmza anahtarı olarak projeye kalıcı bir `test.keystore` tanımlanmıştır. İleride yeni test sürümleri çıktığında araçtaki verileri silmeden doğrudan güncelleme yapabilirsiniz.
